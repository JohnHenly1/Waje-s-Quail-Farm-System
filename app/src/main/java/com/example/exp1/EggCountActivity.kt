package com.example.exp1

import android.Manifest
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

    // ── Cached views ──────────────────────────────────────────────────────────
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: DetectionOverlayView
    private lateinit var liveScanLabel: TextView
    private lateinit var statusBanner: TextView
    private lateinit var captureBtn: Button
    private lateinit var retakeBtn: Button
    private lateinit var modeSwitchBtn: Button
    private lateinit var frozenOverlay: View

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
            // Success — show a brief green banner then hide it
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
            // Fallback: grab preview bitmap
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
                // Replace counts with exact count from this frame
                gradeA = 0; gradeB = 0; gradeC = 0; countedBoxes.clear()
                for (det in results) when (det.label) {
                    "egg_grade_a" -> gradeA++
                    "egg_grade_b" -> gradeB++
                    "egg_grade_c" -> gradeC++
                }
                overlayView.setResults(results)
                updateCountUI()

                val total = gradeA + gradeB + gradeC
                liveScanLabel.text = "● CAPTURED"
                captureBtn.isEnabled = true
                showBanner(
                    if (detector != null) "Found $total egg(s). Tap ↩ to scan again."
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
        resetCounts()
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
        buildCollectionLog()
    }

    private fun updateWeekLabel() {
        findViewById<TextView>(R.id.weekRangeTxt).text = when (weekOffset) {
            0  -> "This Week"
            -1 -> "Last Week"
            else -> "${kotlin.math.abs(weekOffset)} Weeks Ago"
        }
    }

    private fun buildCollectionLog() {
        val container = findViewById<LinearLayout>(R.id.collectionLogList)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val today    = sdf.format(Calendar.getInstance().time)
        val cal      = Calendar.getInstance().apply { add(Calendar.WEEK_OF_YEAR, weekOffset) }

        repeat(7) {
            val dateStr = sdf.format(cal.time)
            val item    = inflater.inflate(R.layout.item_collection_log, container, false)
            item.findViewById<TextView>(R.id.logDate).text   = dateStr
            item.findViewById<TextView>(R.id.logTotal).text  = "0"
            item.findViewById<TextView>(R.id.logGradeA).text = "0"
            item.findViewById<TextView>(R.id.logGradeB).text = "0"
            item.findViewById<TextView>(R.id.logGradeC).text = "0"
            item.findViewById<TextView>(R.id.todayBadge).visibility =
                if (dateStr == today) View.VISIBLE else View.GONE
            container.addView(item)
            cal.add(Calendar.DAY_OF_YEAR, -1)
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
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        detector?.close()
    }

    companion object {
        private const val TAG        = "EggCountActivity"
        private const val IOU_DEDUP  = 0.4f
    }
}