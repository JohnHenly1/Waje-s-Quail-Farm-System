package com.example.exp1

import android.Manifest
import android.app.DatePickerDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class EggCountActivity : AppCompatActivity() {

    // ── Week navigation ───────────────────────────────────────────────────────
    private var weekOffset = 0
    private val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private val dbDateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // ── YOLO detector (null = failed to load, camera still works) ─────────────
    private var detector: YoloDetector? = null

    // ── Camera ────────────────────────────────────────────────────────────────
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null

    // ── Mode: true = live continuous  /  false = frozen on captured frame ─────
    private var isLiveMode = true
    private val analyzing = AtomicBoolean(false)

    // ── Egg counts ────────────────────────────────────────────────────────────
    private var gradeA = 0
    private var gradeB = 0
    private var gradeC = 0
    /** Bounding boxes already counted in live mode (dedup) */
    private val countedBoxes = mutableListOf<android.graphics.RectF>()

    // ── Firebase Realtime Database ────────────────────────────────────────────
    private val database by lazy { FirebaseDatabase.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }
    /** Realtime listener for the collection history — kept so we can remove it */
    private var historyListener: ValueEventListener? = null
    private var historyRef: com.google.firebase.database.DatabaseReference? = null
    /** Cached collection records for populating log without re-fetching */
    private var collectionRecords = mutableMapOf<String, Map<String, Any>>()

    // ── Cached views ──────────────────────────────────────────────────────────
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: DetectionOverlayView
    private lateinit var liveScanLabel: TextView
    private lateinit var statusBanner: TextView
    private lateinit var captureBtn: Button
    private lateinit var retakeBtn: Button
    private lateinit var modeSwitchBtn: Button
    private lateinit var frozenOverlay: View
    private lateinit var saveBtn: Button
    private lateinit var liveTimeText: TextView

    // ── Live time update ──────────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            val now = Calendar.getInstance()
            val timeFormat = SimpleDateFormat("hh:mm a\nMMM d, yyyy", Locale.getDefault())
            liveTimeText.text = timeFormat.format(now.time)
            handler.postDelayed(this, 1000)
        }
    }

    // ── Permission / gallery launchers ────────────────────────────────────────
    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) bindCamera()
        else toast("Camera permission required to scan eggs")
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val bmp = uriToBitmap(uri) ?: return@registerForActivityResult
        freezeAndAnalyze(bmp)
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_egg_count)

        try {
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        } catch (_: Exception) {}

        cacheViews()
        wireListeners()
        setupUI()
        NavigationHelper.setupBottomNavigation(this)

        // Load YOLO — degrades gracefully if model can't be loaded
        tryLoadDetector()

        // Start camera
        cameraExecutor = Executors.newSingleThreadExecutor()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) bindCamera()
        else requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  View wiring
    // ─────────────────────────────────────────────────────────────────────────

    private fun cacheViews() {
        previewView   = findViewById(R.id.cameraPreview)
        overlayView   = findViewById(R.id.detectionOverlay)
        liveScanLabel = findViewById(R.id.liveScanLabel)
        statusBanner  = findViewById(R.id.statusBanner)
        captureBtn    = findViewById(R.id.captureBtn)
        retakeBtn     = findViewById(R.id.retakeBtn)
        modeSwitchBtn = findViewById(R.id.modeSwitchBtn)
        frozenOverlay = findViewById(R.id.frozenOverlay)
        saveBtn       = findViewById(R.id.saveCollectionBtn)
        liveTimeText  = findViewById(R.id.liveTimeText)
    }

    private fun wireListeners() {
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.refreshButton).setOnClickListener { resetCounts() }

        captureBtn.setOnClickListener   { captureFrame() }
        retakeBtn.setOnClickListener    { resumeLive()   }
        modeSwitchBtn.setOnClickListener {
            if (isLiveMode) pickImageLauncher.launch("image/*")
            else resumeLive()
        }

        // Save today's egg count to Firebase Realtime Database
        saveBtn.setOnClickListener { saveCollectionToDatabase() }

        findViewById<View>(R.id.calendarBtn).setOnClickListener { openCalendarPicker() }
        findViewById<View>(R.id.prevWeekBtn).setOnClickListener { weekOffset--; setupUI() }
        findViewById<View>(R.id.nextWeekBtn).setOnClickListener {
            if (weekOffset < 0) { weekOffset++; setupUI() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  YOLO model loading
    // ─────────────────────────────────────────────────────────────────────────

    private fun tryLoadDetector() {
        try {
            detector = YoloDetector(this)
            showBanner("✓ YOLO model ready — point camera at eggs", isError = false, autoHide = true)
        } catch (e: Exception) {
            Log.e(TAG, "YoloDetector init failed", e)
            val msg = buildModelErrorMessage(e)
            showBanner(msg, isError = true, autoHide = false)
        }
    }

    private fun buildModelErrorMessage(e: Exception): String {
        val raw = e.message ?: ""
        return when {
            raw.contains("opset", ignoreCase = true) ->
                "⚠ Model opset error — re-export your YOLO model with opset=19:\n" +
                        "  model.export(format='onnx', opset=19)\n" +
                        "Camera preview is still active."

            raw.contains("FileNotFoundException", ignoreCase = true) ||
                    raw.contains("my_model", ignoreCase = true) ->
                "⚠ my_model.onnx not found in assets/.\n" +
                        "Add it at app/src/main/assets/ and rebuild.\n" +
                        "Camera preview is still active."

            else ->
                "⚠ Model error: ${raw.take(120)}\nCamera preview is still active."
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Camera
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
                .also { imageCapture = it }

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also { ia ->
                    ia.setAnalyzer(cameraExecutor) { proxy -> onLiveFrame(proxy) }
                }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, capture, analyzer
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                toast("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Live frame processing
    // ─────────────────────────────────────────────────────────────────────────

    private fun onLiveFrame(proxy: ImageProxy) {
        if (!isLiveMode || !analyzing.compareAndSet(false, true)) {
            proxy.close(); return
        }
        val bmp: Bitmap = try { proxy.toBitmap() } finally { proxy.close() }
        val results = runDetection(bmp)
        runOnUiThread {
            overlayView.setResults(results)
            if (isLiveMode) addNewEggs(results)
            analyzing.set(false)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Capture a single frame and analyze it
    // ─────────────────────────────────────────────────────────────────────────

    private fun captureFrame() {
        val cap = imageCapture
        if (cap == null) {
            val bmp = previewView.bitmap
            if (bmp != null) freezeAndAnalyze(bmp)
            else toast("Camera not ready yet")
            return
        }

        captureBtn.isEnabled = false
        showBanner("Capturing…", isError = false, autoHide = true)

        cap.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(proxy: ImageProxy) {
                val bmp = proxy.toBitmap(); proxy.close()
                runOnUiThread { freezeAndAnalyze(bmp) }
            }
            override fun onError(exc: ImageCaptureException) {
                Log.e(TAG, "Capture failed", exc)
                runOnUiThread { captureBtn.isEnabled = true; toast("Capture failed") }
            }
        })
    }

    /** Freeze live feed, run YOLO on [bmp], display results */
    private fun freezeAndAnalyze(bmp: Bitmap) {
        isLiveMode = false
        frozenOverlay.visibility = View.VISIBLE
        liveScanLabel.text = "● ANALYZING"
        captureBtn.visibility = View.GONE
        retakeBtn.visibility  = View.VISIBLE
        modeSwitchBtn.text    = "↩ Live Mode"

        cameraExecutor.execute {
            val results = runDetection(bmp)
            runOnUiThread {
                gradeA = 0; gradeB = 0; gradeC = 0; countedBoxes.clear()
                for (det in results) when (det.label) {
                    "Quail_Egg_Grade_A" -> gradeA++
                    "Quail_Egg_Grade_B" -> gradeB++
                    "Quail_Egg_Grade_C" -> gradeC++
                }
                overlayView.setResults(results)
                updateCountUI()

                val total = gradeA + gradeB + gradeC
                liveScanLabel.text = "● CAPTURED"
                captureBtn.isEnabled = true

                // Show save button after capture so user can confirm & save
                saveBtn.visibility = View.VISIBLE

                showBanner(
                    if (detector != null) "Found $total egg(s). Tap 'Save Collection' to record."
                    else "Detection disabled — model error. See banner above.",
                    isError = detector == null,
                    autoHide = detector != null
                )
                trySaveToGallery(bmp)
            }
        }
    }

    private fun resumeLive() {
        isLiveMode = true
        frozenOverlay.visibility = View.GONE
        overlayView.setResults(emptyList())
        liveScanLabel.text    = "● LIVE SCAN"
        captureBtn.visibility = View.VISIBLE
        retakeBtn.visibility  = View.GONE
        modeSwitchBtn.text    = "Pick Photo"
        saveBtn.visibility    = View.GONE
        resetCounts()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Firebase: Save today's collection to Realtime Database
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Data is stored under:
     *   egg_collections / {dateKey} / total
     *                              / gradeA
     *                              / gradeB
     *                              / gradeC
     *                              / timestamp
     *                              / savedBy  (uid of the user who saved)
     *
     * Using the date (yyyy-MM-dd) as the node key means one authoritative
     * record per day.  Subsequent saves on the same day overwrite the node
     * so the latest scan is always reflected.
     */
    private fun saveCollectionToDatabase() {
        val total = gradeA + gradeB + gradeC
        if (total == 0) {
            toast("No eggs detected yet — nothing to save.")
            return
        }

        saveBtn.isEnabled = false
        val today = dbDateFmt.format(Calendar.getInstance().time)

        val record = mapOf(
            "date"      to today,
            "total"     to total,
            "gradeA"    to gradeA,
            "gradeB"    to gradeB,
            "gradeC"    to gradeC,
            "timestamp" to System.currentTimeMillis(),
            "savedBy"   to (auth.currentUser?.uid ?: "unknown")
        )

        database.getReference("egg_collections")
            .child(today)
            .setValue(record)
            .addOnSuccessListener {
                showBanner("✓ Saved $total eggs for $today", isError = false, autoHide = true)
                saveBtn.isEnabled = true
                saveBtn.visibility = View.GONE
                // The persistent ValueEventListener on egg_collections will fire
                // automatically when this write lands, updating the log in real time.
                toast("Collection saved!")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Save failed", e)
                showBanner("✗ Save failed: ${e.message?.take(80)}", isError = true, autoHide = false)
                saveBtn.isEnabled = true
                toast("Save failed — check internet connection.")
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Firebase: Load collection history for the displayed week
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Attaches a realtime ValueEventListener to the egg_collections node.
     * Any time a new save happens (from this device or another), the log
     * updates automatically.
     */
    private fun loadCollectionHistory() {
        // Remove any previous listener before re-attaching
        historyListener?.let { historyRef?.removeEventListener(it) }

        val ref = database.getReference("egg_collections")
        historyRef = ref

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Build date → record map
                val records = mutableMapOf<String, Map<String, Any>>()
                for (child in snapshot.children) {
                    val key  = child.key ?: continue
                    @Suppress("UNCHECKED_CAST")
                    val data = child.value as? Map<String, Any> ?: continue
                    records[key] = data
                }
                collectionRecords = records // Cache the records
                runOnUiThread { populateCollectionLog(records) }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "History load cancelled: ${error.message}")
            }
        }

        ref.addValueEventListener(listener)
        historyListener = listener
    }

    private fun openCalendarPicker() {
        val cal = Calendar.getInstance()
        val datePicker = DatePickerDialog(this, { _, year, month, day ->
            val selectedCal = Calendar.getInstance().apply { set(year, month, day) }
            // Calculate weekOffset
            // First, get the Monday of the current week
            val currentWeekMonday = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            }
            // Monday of selected week
            val selectedWeekMonday = selectedCal.clone() as Calendar
            selectedWeekMonday.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            // Difference in weeks
            val diff = ((selectedWeekMonday.timeInMillis - currentWeekMonday.timeInMillis) / (7 * 24 * 60 * 60 * 1000)).toInt()
            weekOffset = diff
            setupUI()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
        datePicker.show()
    }

    /**
     * Fills the 7-day collection log with data fetched from the database.
     * Days with no data show zeros (same behaviour as before).
     */
    private fun populateCollectionLog(records: Map<String, Map<String, Any>>) {
        val container = findViewById<LinearLayout>(R.id.collectionLogList)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val todayStr = sdf.format(Calendar.getInstance().time)
        val cal = Calendar.getInstance().apply {
            // Set to Monday of the week
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            // Add weekOffset weeks
            add(Calendar.WEEK_OF_YEAR, weekOffset)
        }
        val dayNames = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        repeat(7) {
            val displayDate = sdf.format(cal.time)
            val dayOfWeek = dayNames[cal.get(Calendar.DAY_OF_WEEK) - 1]
            val fullDateText = "$dayOfWeek, $displayDate"
            val dbDate = dbDateFmt.format(cal.time)
            val record = records[dbDate]
            val total = (record?.get("total") as? Long)?.toInt() ?: 0
            val gA = (record?.get("gradeA") as? Long)?.toInt() ?: 0
            val gB = (record?.get("gradeB") as? Long)?.toInt() ?: 0
            val gC = (record?.get("gradeC") as? Long)?.toInt() ?: 0
            val item = inflater.inflate(R.layout.item_collection_log, container, false)
            item.findViewById<TextView>(R.id.logDate).text = fullDateText
            item.findViewById<TextView>(R.id.logTotal).text = total.toString()
            item.findViewById<TextView>(R.id.logGradeA).text = gA.toString()
            item.findViewById<TextView>(R.id.logGradeB).text = gB.toString()
            item.findViewById<TextView>(R.id.logGradeC).text = gC.toString()
            item.findViewById<TextView>(R.id.todayBadge).visibility =
                if (displayDate == todayStr) View.VISIBLE else View.GONE
            container.addView(item)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Detection helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun runDetection(bmp: Bitmap): List<DetectionResult> =
        try { detector?.detect(bmp) ?: emptyList() }
        catch (e: Exception) { Log.e(TAG, "Detection error", e); emptyList() }

    /** Live mode: only count eggs not yet seen (IoU dedup) */
    private fun addNewEggs(results: List<DetectionResult>) {
        for (det in results) {
            val box   = det.boundingBox
            val isNew = countedBoxes.none { iou(box, it) > IOU_DEDUP }
            if (isNew) {
                countedBoxes += box
                when (det.label) {
                    "Quail_Egg_Grade_A" -> gradeA++
                    "Quail_Egg_Grade_B" -> gradeB++
                    "Quail_Egg_Grade_C" -> gradeC++
                }
                updateCountUI()
            }
        }
    }

    private fun iou(a: android.graphics.RectF, b: android.graphics.RectF): Float {
        val il = maxOf(a.left, b.left); val it = maxOf(a.top, b.top)
        val ir = minOf(a.right, b.right); val ib = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, ir - il) * maxOf(0f, ib - it)
        if (inter == 0f) return 0f
        return inter / ((a.right-a.left)*(a.bottom-a.top) +
                (b.right-b.left)*(b.bottom-b.top) - inter)
    }

    private fun resetCounts() {
        gradeA = 0; gradeB = 0; gradeC = 0; countedBoxes.clear()
        overlayView.setResults(emptyList())
        updateCountUI()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateCountUI() {
        val total = gradeA + gradeB + gradeC
        findViewById<TextView>(R.id.todayTotalValue).text = total.toString()
        findViewById<TextView>(R.id.gradeAValue).text     = gradeA.toString()
        findViewById<TextView>(R.id.gradeBValue).text     = gradeB.toString()
        findViewById<TextView>(R.id.gradeCValue).text     = gradeC.toString()
        val pct = if (total > 0) { a: Int -> "${"%.0f".format(a * 100f / total)}%" }
        else { _: Int -> "0%" }
        findViewById<TextView>(R.id.gradeAPercent).text = pct(gradeA)
        findViewById<TextView>(R.id.gradeBPercent).text = pct(gradeB)
        findViewById<TextView>(R.id.gradeCPercent).text = pct(gradeC)
    }

    private fun showBanner(msg: String, isError: Boolean, autoHide: Boolean) {
        statusBanner.text = msg
        statusBanner.setBackgroundColor(
            if (isError) Color.parseColor("#B71C1C") else Color.parseColor("#1B5E20")
        )
        statusBanner.visibility = View.VISIBLE
        if (autoHide) {
            Handler(Looper.getMainLooper()).postDelayed(
                { statusBanner.visibility = View.GONE }, 3_500
            )
        }
    }

    private fun setupUI() {
        updateCountUI()
        updateWeekLabel()
        populateCollectionLog(collectionRecords)
        // Hide Save button initially; it appears after a capture
        saveBtn.visibility = View.GONE
    }

    private fun updateWeekLabel() {
        findViewById<TextView>(R.id.weekRangeTxt).text = when (weekOffset) {
            0  -> "This Week"
            -1 -> "Last Week"
            else -> "${kotlin.math.abs(weekOffset)} Weeks Ago"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Utility
    // ─────────────────────────────────────────────────────────────────────────

    private fun uriToBitmap(uri: Uri): Bitmap? = try {
        contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it)
        }
    } catch (e: Exception) { Log.e(TAG, "uriToBitmap", e); null }

    private fun trySaveToGallery(bmp: Bitmap) {
        try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME,
                    "egg_scan_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QuailFarm")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            uri?.let { contentResolver.openOutputStream(it)?.use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }}
        } catch (e: Exception) { Log.w(TAG, "Gallery save failed", e) }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle: manage Realtime Database listener
    // ─────────────────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        // Re-attach listener every time the screen becomes visible so that
        // any collections saved from another device (or another session) are
        // reflected immediately without needing a manual refresh.
        loadCollectionHistory()
        handler.post(timeUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        // Detach while off-screen to avoid unnecessary network traffic and
        // the risk of updating a detached view hierarchy.
        historyListener?.let { historyRef?.removeEventListener(it) }
        historyListener = null
        handler.removeCallbacks(timeUpdateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        detector?.close()
        // Belt-and-suspenders: remove listener if onPause was somehow skipped
        historyListener?.let { historyRef?.removeEventListener(it) }
    }

    companion object {
        private const val TAG        = "EggCountActivity"
        private const val IOU_DEDUP  = 0.4f
    }
}