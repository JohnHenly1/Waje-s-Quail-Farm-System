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
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
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
    private var cameraProvider: ProcessCameraProvider? = null

    // ── Mode: camera is OFF by default, user must manually activate ───────────
    private var isLiveMode = false
    private val analyzing = AtomicBoolean(false)

    // ── Double-tap to stop camera ─────────────────────────────────────────────
    private var lastTapTime = 0L
    private val doubleTapInterval = 400L

    // ── Egg counts ────────────────────────────────────────────────────────────
    private var gradeA = 0
    private var gradeB = 0
    private var gradeC = 0
    private val countedBoxes = mutableListOf<android.graphics.RectF>()

    // ── Price / Revenue / Profit ──────────────────────────────────────────────
    /**
     * Price per egg (PHP) for Grade A — Normal (full price).
     * Grade B — Cracked = pricePerEgg * 0.5
     * Grade C — Reject  = ₱0.00 (not sold)
     */
    private var pricePerEgg: Double = 3.50
    private var dailyExpenses: Double = 0.0

    // ── Derived per-grade prices (read-only helpers) ──────────────────────────
    private val priceGradeA get() = pricePerEgg          // full price
    private val priceGradeB get() = pricePerEgg * 0.5    // half price
    private val priceGradeC get() = 0.0                  // reject — not sold

    /** Compute revenue from individual grade counts using grade-specific prices. */
    private fun calcRevenue(a: Int, b: Int, @Suppress("UNUSED_PARAMETER") c: Int): Double =
        (a * priceGradeA) + (b * priceGradeB) // Grade C contributes ₱0

    // ── Firebase Realtime Database ────────────────────────────────────────────
    private val database by lazy { FirebaseDatabase.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }
    private var historyListener: ValueEventListener? = null
    private var historyRef: com.google.firebase.database.DatabaseReference? = null
    private var collectionRecords = mutableMapOf<String, Map<String, Any>>()

    // ── Firestore ─────────────────────────────────────────────────────────────
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private var pricingListener: ListenerRegistration? = null

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
        if (granted) { isLiveMode = true; bindCamera() }
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

        tryLoadDetector()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Grade helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun gradeDisplayName(grade: String): String = when (grade) {
        "A" -> "Grade A — Normal"
        "B" -> "Grade B — Cracked"
        "C" -> "Grade C — Reject"
        else -> grade
    }

    private fun gradeTag(grade: String): String = when (grade) {
        "A" -> "Normal"
        "B" -> "Cracked"
        "C" -> "Reject"
        else -> grade
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

        captureBtn.setOnClickListener    { captureFrame() }
        retakeBtn.setOnClickListener     { resumeLive()   }
        modeSwitchBtn.setOnClickListener {
            if (isLiveMode) pickImageLauncher.launch("image/*")
            else resumeLive()
        }

        saveBtn.setOnClickListener { saveCollectionToDatabase() }

        findViewById<View>(R.id.priceSettingsBtn)?.setOnClickListener { showPriceDialog() }

        findViewById<View>(R.id.calendarBtn).setOnClickListener { openCalendarPicker() }
        findViewById<View>(R.id.prevWeekBtn).setOnClickListener { weekOffset--; setupUI() }
        findViewById<View>(R.id.nextWeekBtn).setOnClickListener {
            if (weekOffset < 0) { weekOffset++; setupUI() }
        }

        previewView.setOnClickListener {
            val now = System.currentTimeMillis()
            val isSingleActivation = imageCapture == null

            if (isSingleActivation) {
                startCameraIfNeeded()
                return@setOnClickListener
            }

            if (now - lastTapTime <= doubleTapInterval) {
                stopCamera()
                lastTapTime = 0L
            } else {
                lastTapTime = now
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  YOLO model loading
    // ─────────────────────────────────────────────────────────────────────────

    private fun tryLoadDetector() {
        try {
            detector = YoloDetector(this)
            showBanner("✓ YOLO model ready — tap preview to activate camera", isError = false, autoHide = true)
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
    //  Camera activation
    // ─────────────────────────────────────────────────────────────────────────

    private fun startCameraIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            isLiveMode = true
            liveScanLabel.text = "● LIVE SCAN"
            frozenOverlay.visibility = View.GONE
            bindCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        isLiveMode = false
        analyzing.set(false)
        overlayView.setResults(emptyList())
        frozenOverlay.visibility = View.VISIBLE
        liveScanLabel.text = "● CAMERA OFF"
        captureBtn.visibility = View.VISIBLE
        retakeBtn.visibility  = View.GONE
        modeSwitchBtn.text    = "Pick Photo"
        saveBtn.visibility    = View.GONE
        resetCounts()
        showBanner("Camera stopped — tap preview to restart", isError = false, autoHide = true)
        toast("Double-tap detected — camera off")
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

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
        if (imageCapture == null) {
            startCameraIfNeeded()
            toast("Camera activating — try again in a moment")
            return
        }

        captureBtn.isEnabled = false
        showBanner("Capturing…", isError = false, autoHide = true)

        imageCapture!!.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(proxy: ImageProxy) {
                val bmp = proxy.toBitmap(); proxy.close()
                runOnUiThread { freezeAndAnalyze(bmp, true) }
            }
            override fun onError(exc: ImageCaptureException) {
                Log.e(TAG, "Capture failed", exc)
                runOnUiThread { captureBtn.isEnabled = true; toast("Capture failed") }
            }
        })
    }

    private fun freezeAndAnalyze(bmp: Bitmap, saveToGallery: Boolean = false) {
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
                saveBtn.visibility = View.VISIBLE

                showBanner(
                    if (detector != null)
                        "Found $total egg(s) — " +
                                "A:$gradeA Normal · B:$gradeB Cracked · C:$gradeC Reject\n" +
                                "Tap 'Save Collection' to record."
                    else
                        "Detection disabled — model error. See banner above.",
                    isError = detector == null,
                    autoHide = detector != null
                )
                if (saveToGallery) trySaveToGallery(bmp)
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
        saveBtn.text      = "Save Collection"
        saveBtn.isEnabled = true
        saveBtn.visibility = View.GONE
        resetCounts()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Firebase: Save today's collection to Realtime Database
    // ─────────────────────────────────────────────────────────────────────────

    private fun saveCollectionToDatabase() {
        val total = gradeA + gradeB + gradeC
        if (total == 0) {
            toast("No eggs detected yet — nothing to save.")
            return
        }

        saveBtn.isEnabled = false
        val today = dbDateFmt.format(Calendar.getInstance().time)
        val ref = database.getReference("egg_collections").child(today)

        ref.get().addOnSuccessListener { snapshot ->
            val prevTotal = snapshot.child("total").getValue(Int::class.java) ?: 0
            val prevA = snapshot.child("gradeA").getValue(Int::class.java) ?: 0
            val prevB = snapshot.child("gradeB").getValue(Int::class.java) ?: 0
            val prevC = snapshot.child("gradeC").getValue(Int::class.java) ?: 0

            val updatedTotal = prevTotal + total
            val updatedA = prevA + gradeA
            val updatedB = prevB + gradeB
            val updatedC = prevC + gradeC

            // ── Grade-specific pricing ────────────────────────────────────────
            // Grade A (Normal)  = full pricePerEgg
            // Grade B (Cracked) = 50% of pricePerEgg
            // Grade C (Reject)  = ₱0.00 — not sold
            val revenue   = calcRevenue(updatedA, updatedB, updatedC)
            val netProfit = revenue - dailyExpenses

            val updatedRecord = mapOf(
                "date"          to today,
                "total"         to updatedTotal,
                "gradeA"        to updatedA,
                "gradeB"        to updatedB,
                "gradeC"        to updatedC,
                "pricePerEgg"   to pricePerEgg,          // Grade A full price
                "priceGradeA"   to priceGradeA,          // same as pricePerEgg
                "priceGradeB"   to priceGradeB,          // half price
                "priceGradeC"   to priceGradeC,          // ₱0
                "revenue"       to revenue,
                "expenses"      to dailyExpenses,
                "netProfit"     to netProfit,
                "timestamp"     to System.currentTimeMillis(),
                "savedBy"       to (auth.currentUser?.uid ?: "unknown")
            )

            ref.setValue(updatedRecord)
                .addOnSuccessListener {
                    showBanner(
                        "✓ Saved $updatedTotal eggs — " +
                                "A:$updatedA Normal · B:$updatedB Cracked · C:$updatedC Reject\n" +
                                "Revenue: ₱${"%.2f".format(revenue)}  " +
                                "(A@₱${"%.2f".format(priceGradeA)} · " +
                                "B@₱${"%.2f".format(priceGradeB)} · " +
                                "C=₱0.00)",
                        isError = false,
                        autoHide = true
                    )
                    saveBtn.text = "✓ Saved"
                    saveBtn.isEnabled = false
                    toast("Collection updated!")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Save failed", e)
                    showBanner(
                        "✗ Save failed: ${e.message?.take(80)}",
                        isError = true,
                        autoHide = false
                    )
                    saveBtn.isEnabled = true
                    toast("Save failed — check internet connection.")
                }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Firebase: Load collection history for the displayed week
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadCollectionHistory() {
        historyListener?.let { historyRef?.removeEventListener(it) }

        val ref = database.getReference("egg_collections")
        historyRef = ref

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val records = mutableMapOf<String, Map<String, Any>>()
                for (child in snapshot.children) {
                    val key  = child.key ?: continue
                    @Suppress("UNCHECKED_CAST")
                    val data = child.value as? Map<String, Any> ?: continue
                    records[key] = data
                }
                collectionRecords = records
                runOnUiThread { populateCollectionLog(records) }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "History load cancelled: ${error.message}")
            }
        }

        ref.addValueEventListener(listener)
        historyListener = listener
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Firestore: Centralised egg pricing from farm_data/shared
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadPricingFromFirestore() {
        pricingListener = firestore
            .collection("farm_data")
            .document("shared")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                snapshot.getDouble("pricePerEgg")?.let { pricePerEgg = it }
                snapshot.getDouble("dailyExpenses")?.let { dailyExpenses = it }
                runOnUiThread { updateCountUI() }
            }
    }

    private fun openCalendarPicker() {
        val cal = Calendar.getInstance()
        val datePicker = DatePickerDialog(this, { _, year, month, day ->
            val selectedCal = Calendar.getInstance().apply { set(year, month, day) }
            val currentWeekMonday = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            }
            val selectedWeekMonday = selectedCal.clone() as Calendar
            selectedWeekMonday.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            val diff = ((selectedWeekMonday.timeInMillis - currentWeekMonday.timeInMillis) /
                    (7 * 24 * 60 * 60 * 1000)).toInt()
            weekOffset = diff
            setupUI()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
        datePicker.show()
    }

    private fun populateCollectionLog(records: Map<String, Map<String, Any>>) {
        val container = findViewById<LinearLayout>(R.id.collectionLogList)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val todayStr = sdf.format(Calendar.getInstance().time)

        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            add(Calendar.WEEK_OF_YEAR, weekOffset)
        }
        val dayNames = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday",
            "Thursday", "Friday", "Saturday")

        repeat(7) {
            val displayDate  = sdf.format(cal.time)
            val dayOfWeek    = dayNames[cal.get(Calendar.DAY_OF_WEEK) - 1]
            val fullDateText = "$dayOfWeek, $displayDate"
            val dbDate  = dbDateFmt.format(cal.time)
            val record  = records[dbDate]

            val total = (record?.get("total")  as? Long)?.toInt() ?: 0
            val gA    = (record?.get("gradeA") as? Long)?.toInt() ?: 0
            val gB    = (record?.get("gradeB") as? Long)?.toInt() ?: 0
            val gC    = (record?.get("gradeC") as? Long)?.toInt() ?: 0

            // ── Grade-specific revenue fallback ───────────────────────────────
            // If a stored revenue exists use it (already computed with correct grades),
            // otherwise re-compute using current grade prices so the log is consistent.
            val revenue   = record?.get("revenue")   as? Double ?: calcRevenue(gA, gB, gC)
            val expenses  = record?.get("expenses")  as? Double ?: 0.0
            val netProfit = record?.get("netProfit") as? Double ?: (revenue - expenses)

            val item = inflater.inflate(R.layout.item_collection_log, container, false)
            item.findViewById<TextView>(R.id.logDate).text  = fullDateText
            item.findViewById<TextView>(R.id.logTotal).text = total.toString()

            item.findViewById<TextView>(R.id.logGradeA).text           = "$gA"
            item.findViewById<TextView>(R.id.logGradeALabel)?.text     = "Normal"

            item.findViewById<TextView>(R.id.logGradeB).text           = "$gB"
            item.findViewById<TextView>(R.id.logGradeBLabel)?.text     = "Cracked"

            item.findViewById<TextView>(R.id.logGradeC).text           = "$gC"
            item.findViewById<TextView>(R.id.logGradeCLabel)?.text     = "Reject"

            item.findViewById<TextView>(R.id.logRevenue)?.text   = "₱${"%.2f".format(revenue)}"
            item.findViewById<TextView>(R.id.logNetProfit)?.text = "₱${"%.2f".format(netProfit)}"
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
        return inter / ((a.right - a.left) * (a.bottom - a.top) +
                (b.right - b.left) * (b.bottom - b.top) - inter)
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

        // ── Grade-specific revenue ────────────────────────────────────────────
        // Grade A (Normal)  — full pricePerEgg
        // Grade B (Cracked) — 50% of pricePerEgg
        // Grade C (Reject)  — ₱0.00, not included in revenue
        val revenue   = calcRevenue(gradeA, gradeB, gradeC)
        val netProfit = revenue - dailyExpenses

        findViewById<TextView>(R.id.todayTotalValue).text = total.toString()

        // Grade A — Normal (full price)
        findViewById<TextView>(R.id.gradeAValue).text  = gradeA.toString()
        findViewById<TextView>(R.id.gradeADesc)?.text  = "Normal"

        // Grade B — Cracked (half price)
        findViewById<TextView>(R.id.gradeBValue).text  = gradeB.toString()
        findViewById<TextView>(R.id.gradeBDesc)?.text  = "Cracked"

        // Grade C — Reject (no revenue)
        findViewById<TextView>(R.id.gradeCValue).text  = gradeC.toString()
        findViewById<TextView>(R.id.gradeCDesc)?.text  = "Reject"

        val pct = if (total > 0) { a: Int -> "${"%.0f".format(a * 100f / total)}%" }
        else { _: Int -> "0%" }
        findViewById<TextView>(R.id.gradeAPercent).text = pct(gradeA)
        findViewById<TextView>(R.id.gradeBPercent).text = pct(gradeB)
        findViewById<TextView>(R.id.gradeCPercent).text = pct(gradeC)

        // Revenue reflects grade-specific pricing
        findViewById<TextView>(R.id.revenueValue)?.text     = "₱${"%.2f".format(revenue)}"
        findViewById<TextView>(R.id.netProfitValue)?.text   = "₱${"%.2f".format(netProfit)}"
        // Show Grade A price as the reference price per egg
        findViewById<TextView>(R.id.pricePerEggValue)?.text = "₱${"%.2f".format(priceGradeA)}/egg (A)"
    }

    /**
     * Revenue / price settings dialog.
     * Grade A = set price | Grade B = 50% | Grade C = ₱0 (auto-derived, not editable).
     * Changes persist to Firestore farm_data/shared.
     */
    private fun showPriceDialog() {
        val ctx = this
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }
        val priceEdit = android.widget.EditText(ctx).apply {
            hint = "Grade A price per egg (₱) — full price"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(pricePerEgg.toString())
        }
        val expenseEdit = android.widget.EditText(ctx).apply {
            hint = "Daily expenses (₱) — feed, etc."
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(dailyExpenses.toString())
        }

        // Informational labels so the user understands grade pricing
        layout.addView(android.widget.TextView(ctx).apply {
            text = "Grade A — Normal price per egg (₱)"
        })
        layout.addView(priceEdit)
        layout.addView(android.widget.TextView(ctx).apply {
            text = "Grade B — Cracked (auto: 50% of Grade A)"
            setPadding(0, 16, 0, 0)
            setTextColor(android.graphics.Color.GRAY)
            textSize = 12f
        })
        layout.addView(android.widget.TextView(ctx).apply {
            text = "Grade C — Reject (auto: ₱0.00, not sold)"
            setPadding(0, 4, 0, 16)
            setTextColor(android.graphics.Color.GRAY)
            textSize = 12f
        })
        layout.addView(android.widget.TextView(ctx).apply {
            text = "Daily expenses (₱)"
        })
        layout.addView(expenseEdit)

        android.app.AlertDialog.Builder(ctx)
            .setTitle("Revenue Settings")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                val newPrice    = priceEdit.text.toString().toDoubleOrNull() ?: pricePerEgg
                val newExpenses = expenseEdit.text.toString().toDoubleOrNull() ?: dailyExpenses
                pricePerEgg   = newPrice
                dailyExpenses = newExpenses
                updateCountUI()

                val update = mapOf(
                    "pricePerEgg"  to newPrice,
                    "dailyExpenses" to newExpenses
                )
                firestore.collection("farm_data").document("shared")
                    .update(update)
                    .addOnFailureListener {
                        firestore.collection("farm_data").document("shared")
                            .set(update, SetOptions.merge())
                    }
                toast("Pricing updated — B=₱${"%.2f".format(newPrice * 0.5)}, C=₱0.00")
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            uri?.let {
                contentResolver.openOutputStream(it)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            }
        } catch (e: Exception) { Log.w(TAG, "Gallery save failed", e) }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        loadCollectionHistory()
        loadPricingFromFirestore()
        handler.post(timeUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        historyListener?.let { historyRef?.removeEventListener(it) }
        historyListener = null
        pricingListener?.remove()
        pricingListener = null
        handler.removeCallbacks(timeUpdateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        detector?.close()
        cameraProvider?.unbindAll()
        historyListener?.let { historyRef?.removeEventListener(it) }
        pricingListener?.remove()
    }

    companion object {
        private const val TAG       = "EggCountActivity"
        private const val IOU_DEDUP = 0.4f
    }
}