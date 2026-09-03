package com.example.exp1

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

/** A queued batch photo: [full] for detection/saving, [thumb] for the lightweight strip UI,
 *  [results] populated once Done has scanned it (empty until then), and
 *  [fromCamera] — true only for a fresh camera capture, so we know it's safe
 *  to write to the gallery on save (gallery picks are already on disk). */
private data class BatchShot(
    val full: Bitmap,
    val thumb: Bitmap,
    var results: List<DetectionResult> = emptyList(),
    val fromCamera: Boolean = false
)

class EggCountActivity : AppCompatActivity() {

    // ── Capture modes ───────────────────────────────────────────────────────
    private enum class CaptureMode { SINGLE, BATCH }
    private var captureMode = CaptureMode.SINGLE
    private var batchShotCount = 0

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

    // ── Deferred gallery save (SINGLE mode) — the shot + its detections are
    // stashed here after freezing/scanning, and only written to disk once the
    // user confirms Save Collection (see finishSessionAfterSave). ──
    private var singleShotBitmap: Bitmap? = null
    private var singleShotResults: List<DetectionResult> = emptyList()

    // ── Firebase Realtime Database ────────────────────────────────────────────
    private val database by lazy { FirebaseDatabase.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }
    private var historyListener: ValueEventListener? = null
    private var historyRef: com.google.firebase.database.DatabaseReference? = null
    private var collectionRecords = mutableMapOf<String, Map<String, Any>>()

    // ── Cached views ──────────────────────────────────────────────────────────
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: DetectionOverlayView
    private lateinit var liveScanLabel: TextView
    private lateinit var statusBanner: TextView
    private lateinit var captureBtn: Button
    private lateinit var retakeBtn: Button
    private lateinit var discardBtn: Button
    private lateinit var modeSwitchBtn: Button
    private lateinit var captureModeToggleBtn: Button
    private lateinit var frozenOverlay: View
    private lateinit var saveBtn: Button
    private lateinit var liveTimeText: TextView
    private lateinit var gradeARow: View
    private lateinit var gradeBRow: View
    private lateinit var gradeCRow: View

    // ── Frozen/picked photo preview + scanning animation ─────────────────────
    private lateinit var frozenPreviewImage: ImageView
    private lateinit var scanLineView: View
    private var scanAnimator: ObjectAnimator? = null

    // ── Batch queue: shots/picks collected here, NOT scanned until Done ──────
    private val pendingBatchShots = mutableListOf<BatchShot>()
    private var isScanningBatch = false
    private var isPreviewingThumbnail = false
    private lateinit var batchThumbnailScroll: HorizontalScrollView
    private lateinit var batchThumbnailContainer: LinearLayout
    private lateinit var doneBatchBtn: Button

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

    // Multi-select photo picker — queues picks into the batch, doesn't scan yet.
    private val pickMultipleImagesLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_BATCH_PICK)
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        queuePickedPhotos(uris)
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


    private fun shortGradeLabel(label: String): String = when {
        label.endsWith("_A", ignoreCase = true) -> "A"
        label.endsWith("_B", ignoreCase = true) -> "B"
        label.endsWith("_C", ignoreCase = true) -> "C"
        else -> label
    }

    /** Bakes the detection boxes + grade labels onto a copy of [bmp], so the
     * photo saved to the gallery matches what the user reviewed on-screen. */
    private fun drawDetectionsOnBitmap(bmp: Bitmap, results: List<DetectionResult>): Bitmap {
        if (results.isEmpty()) return bmp
        val output = bmp.copy(Bitmap.Config.ARGB_8888, true) ?: return bmp
        val canvas = android.graphics.Canvas(output)
        val strokeW = maxOf(4f, output.width * 0.004f)
        val textSz  = maxOf(28f, output.width * 0.03f)
        val boxPaint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = strokeW
            isAntiAlias = true
        }
        val textPaint = android.graphics.Paint().apply {
            color = Color.WHITE
            textSize = textSz
            isAntiAlias = true
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        for (det in results) {
            val grade = shortGradeLabel(det.label)
            boxPaint.color = when (grade) {
                "A" -> Color.parseColor("#2E7D32")
                "B" -> Color.parseColor("#F57F17")
                "C" -> Color.parseColor("#C62828")
                else -> Color.WHITE
            }
            canvas.drawRect(det.boundingBox, boxPaint)
            val labelY = (det.boundingBox.top - 8f).coerceAtLeast(textSz)
            canvas.drawText("$grade · ${gradeTag(grade)}", det.boundingBox.left, labelY, textPaint)
        }
        return output
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
        discardBtn    = findViewById(R.id.discardBtn)
        modeSwitchBtn = findViewById(R.id.modeSwitchBtn)
        captureModeToggleBtn = findViewById(R.id.captureModeToggleBtn)
        frozenOverlay = findViewById(R.id.frozenOverlay)
        saveBtn       = findViewById(R.id.saveCollectionBtn)
        liveTimeText  = findViewById(R.id.liveTimeText)
        gradeARow     = findViewById(R.id.gradeARow)
        gradeBRow     = findViewById(R.id.gradeBRow)
        gradeCRow     = findViewById(R.id.gradeCRow)

        frozenPreviewImage = findViewById(R.id.frozenPreviewImage)
        scanLineView        = findViewById(R.id.scanLineView)

        batchThumbnailScroll    = findViewById(R.id.batchThumbnailScroll)
        batchThumbnailContainer = findViewById(R.id.batchThumbnailContainer)
        doneBatchBtn             = findViewById(R.id.doneBatchBtn)
    }

    private fun wireListeners() {
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.refreshButton).setOnClickListener { resetCounts() }

        captureBtn.setOnClickListener    { onCaptureBtnClicked() }
        // Retake: either dismisses a thumbnail preview back to live batch capture,
        // or (normal SINGLE-mode flow) discards just the frozen shot and resumes live.
        retakeBtn.setOnClickListener {
            when {
                isPreviewingThumbnail -> dismissThumbnailPreview()
                captureMode == CaptureMode.SINGLE -> confirmRetake()
                else -> resumeLiveKeepCounts()
            }
        }
        // Discard: throw away everything captured so far — now confirmed first.
        discardBtn.setOnClickListener    { confirmDiscard() }
        modeSwitchBtn.setOnClickListener {
            // Decide by the button's current label, not isLiveMode — otherwise
            // tapping "Pick Photo" while the camera is off falls through to
            // resumeLive() and shows a stale/frozen preview instead of the picker.
            if (modeSwitchBtn.text == "↩ Live Mode") {
                confirmLiveModeSwitch()
            } else {
                onPickPhotoClicked()
            }
        }
        captureModeToggleBtn.setOnClickListener { toggleCaptureMode() }
        doneBatchBtn.setOnClickListener { finishBatchAndScan() }

        saveBtn.setOnClickListener { confirmSaveCollection() }

        findViewById<View>(R.id.calendarBtn).setOnClickListener { openCalendarPicker() }
        findViewById<View>(R.id.prevWeekBtn).setOnClickListener { weekOffset--; setupUI() }
        findViewById<View>(R.id.nextWeekBtn).setOnClickListener {
            if (weekOffset < 0) { weekOffset++; setupUI() }
        }

        gradeARow.setOnClickListener { showGradeDetailDialog("A") }
        gradeBRow.setOnClickListener { showGradeDetailDialog("B") }
        gradeCRow.setOnClickListener { showGradeDetailDialog("C") }

        previewView.setOnClickListener {
            val now = System.currentTimeMillis()
            val isSingleActivation = imageCapture == null

            if (isSingleActivation) {
                // Same guard as onCaptureBtnClicked(): if photos were picked
                // and are sitting in the batch preview while the camera isn't
                // bound yet, tapping the preview would silently force-start
                // the camera and wipe that preview — warn instead.
                if (pendingBatchShots.isNotEmpty() && !isScanningBatch) {
                    showBanner(
                        "Camera is off — Discard the queued photo(s) or tap Done to scan them before activating the camera.",
                        isError = true, autoHide = false
                    )
                    toast("Discard the picked photo(s) first, or tap Done to scan them")
                    return@setOnClickListener
                }
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
    //  Capture mode toggle (Single vs Batch)
    // ─────────────────────────────────────────────────────────────────────────

    private fun toggleCaptureMode() {
        // Don't allow switching modes mid-batch with unscanned shots queued —
        // force the user to scan/save or explicitly discard first.
        if (captureMode == CaptureMode.BATCH && pendingBatchShots.isNotEmpty()) {
            toast("Scan, save, or discard the current batch before switching modes")
            return
        }

        captureMode = if (captureMode == CaptureMode.SINGLE) CaptureMode.BATCH else CaptureMode.SINGLE
        captureModeToggleBtn.text =
            if (captureMode == CaptureMode.BATCH) "Mode: Batch" else "Mode: Single"
        captureBtn.text =
            if (captureMode == CaptureMode.BATCH) "Capture (+)" else "Capture"

        batchShotCount = 0
        pendingBatchShots.clear()
        refreshBatchThumbnails()
        doneBatchBtn.visibility = View.GONE
        resetCounts()
        singleShotBitmap = null
        singleShotResults = emptyList()
        captureBtn.alpha = 1f
        updateBatchLabel()
        toast(if (captureMode == CaptureMode.BATCH) "Batch capture ON" else "Batch capture OFF")
    }

    private fun updateBatchLabel() {
        if (!isLiveMode) return
        liveScanLabel.text = if (captureMode == CaptureMode.BATCH)
            "● LIVE — queued: ${pendingBatchShots.size} shot(s)" else "● LIVE SCAN"
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Grade detail popup
    // ─────────────────────────────────────────────────────────────────────────

    private fun gradeDescription(grade: String): String = when (grade) {
        "A" -> "This is what a healthy quail egg looks like. The shell is smooth, " +
                "unbroken, and evenly speckled in light brown/tan spots. The shape is a " +
                "consistent small oval with no dents, cracks, or discoloration. This is " +
                "the grade you want most of your eggs to be."
        "B" -> "This egg grading is either had cracks or in rare cases a softshell " +
                "— usually a hairline crack or a small chip in the shell. You can often " +
                "spot a thin dark line running across the speckled surface. Still usable, " +
                "not sold usually used for fertilization."
        "C" -> "This egg is rejected. Look for major pattern difference — discoloration " +
                "shell, leaking contents, a badly misshapen or crushed form, or shell " +
                "discoloration/mold. These are not sold but this is eaten."
        else -> ""
    }

    private fun gradeImageRes(grade: String): Int = when (grade) {
        "A" -> R.drawable.egg_grade_a
        "B" -> R.drawable.egg_grade_b
        "C" -> R.drawable.egg_grade_c
        else -> R.drawable.egg_grade_a
    }
    private fun showGradeDetailDialog(grade: String) {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.dialog_egg_grade_detail, null)

        view.findViewById<ImageView>(R.id.ivGradeImage).setImageResource(gradeImageRes(grade))
        view.findViewById<TextView>(R.id.tvGradeDescription).text = gradeDescription(grade)

        MaterialAlertDialogBuilder(this)
            .setTitle(gradeDisplayName(grade))
            .setView(view)
            .setPositiveButton("Got it", null)
            .show()
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
            overlayView.setImageSize(0, 0) // back to naive 1:1 mapping for the live camera feed
            updateBatchLabel()
            frozenOverlay.visibility = View.GONE
            frozenPreviewImage.visibility = View.GONE
            captureBtn.alpha = 1f
            stopScanAnimation()
            try {
                bindCamera()
            } catch (e: Exception) {
                // Defensive: never let a stray tap right after a reset (e.g.
                // right after Save Collection tears down camera state) crash
                // the app — surface it as a banner instead.
                Log.e(TAG, "startCameraIfNeeded: bindCamera crashed", e)
                toast("Camera error — tap preview again to retry")
            }
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    /** Double-tap-triggered stop: same as [stopCameraCore] plus the double-tap toast. */
    private fun stopCamera() {
        stopCameraCore()
        toast("Double-tap detected — camera off")
    }

    /** Shared "power the camera off and reset to a clean slate" logic, with no
     * assumptions about *why* it was triggered (double-tap vs. post-save reset). */
    private fun stopCameraCore() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        isLiveMode = false
        analyzing.set(false)
        overlayView.setImageSize(0, 0)
        overlayView.setResults(emptyList())
        // Camera is fully off — go solid black rather than the translucent dim
        // used when reviewing a frozen/captured shot, so it's obvious there's
        // no feed instead of looking like a frozen/stuck frame.
        frozenOverlay.setBackgroundColor(Color.BLACK)
        frozenOverlay.visibility = View.VISIBLE
        frozenPreviewImage.visibility = View.GONE
        stopScanAnimation()
        liveScanLabel.text = "● CAMERA OFF"
        captureBtn.visibility = View.VISIBLE
        captureBtn.alpha      = 1f
        captureBtn.isEnabled  = true
        retakeBtn.visibility  = View.GONE
        discardBtn.visibility = View.GONE
        modeSwitchBtn.text    = "Pick Photo"
        saveBtn.visibility    = View.GONE
        batchShotCount = 0
        resetCounts()
        singleShotBitmap = null
        singleShotResults = emptyList()
        showBanner("Camera stopped — tap preview to restart", isError = false, autoHide = true)
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
    //  SINGLE mode runs live detection continuously and auto-accumulates
    //  results (via IoU dedup). BATCH mode also shows live boxes as a framing
    //  aid, but never auto-adds to the count — batch shots are only queued on
    //  an explicit capture tap and only scanned when the user taps Done.
    // ─────────────────────────────────────────────────────────────────────────

    private fun onLiveFrame(proxy: ImageProxy) {
        if (!isLiveMode || !analyzing.compareAndSet(false, true)) {
            proxy.close(); return
        }
        val bmp: Bitmap = try { proxy.toBitmap() } finally { proxy.close() }
        val results = runDetection(bmp)
        runOnUiThread {
            overlayView.setResults(results)
            if (isLiveMode && captureMode == CaptureMode.SINGLE) addNewEggs(results)
            analyzing.set(false)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Capture a single frame
    //  SINGLE mode: freeze + scan immediately (unchanged behavior).
    //  BATCH mode: just queue the shot as a thumbnail — camera stays live,
    //  nothing gets scanned until the user taps Done.
    // ─────────────────────────────────────────────────────────────────────────

    /** Guards the main capture button: if photos were picked from the gallery
     * and are sitting in the batch preview while the camera isn't live yet,
     * tapping Capture would silently relaunch the camera and wipe that
     * preview — the thumbnails stay queued, but the frozen review is lost.
     * Warn and block instead of letting that happen. */
    private fun onCaptureBtnClicked() {
        // imageCapture is the ground truth for "camera is actually bound" —
        // isLiveMode alone isn't reliable here (e.g. returning from a
        // thumbnail preview can report "live" even if the camera was never
        // started in the first place, which is what caused the crash).
        if (pendingBatchShots.isNotEmpty() && !isScanningBatch && imageCapture == null) {
            showBanner(
                "Camera is off — Discard the queued photo(s) or tap Done to scan them before capturing.",
                isError = true, autoHide = false
            )
            toast("Discard the picked photo(s) first, or tap Done to scan them")
            return
        }
        captureFrame()
    }

    private fun captureFrame() {
        if (!::cameraExecutor.isInitialized || cameraExecutor.isShutdown) {
            toast("Camera not ready — try again in a moment")
            return
        }
        if (imageCapture == null) {
            startCameraIfNeeded()
            toast("Camera activating — try again in a moment")
            return
        }

        captureBtn.isEnabled = false
        showBanner(
            if (captureMode == CaptureMode.BATCH) "Capturing shot…" else "Capturing…",
            isError = false, autoHide = true
        )

        imageCapture!!.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(proxy: ImageProxy) {
                val bmp = proxy.toBitmap(); proxy.close()
                if (captureMode == CaptureMode.BATCH) {
                    // Build the lightweight thumbnail here on the background
                    // executor, so the UI thread only ever handles small bitmaps.
                    val thumb = makeThumb(bmp)
                    runOnUiThread {
                        captureBtn.isEnabled = true
                        addShotToBatchQueue(BatchShot(bmp, thumb, fromCamera = true))
                    }
                } else {
                    runOnUiThread {
                        captureBtn.isEnabled = true
                        freezeAndAnalyze(bmp)
                    }
                }
            }
            override fun onError(exc: ImageCaptureException) {
                Log.e(TAG, "Capture failed", exc)
                runOnUiThread { captureBtn.isEnabled = true; toast("Capture failed") }
            }
        })
    }

    /**
     * SINGLE-mode only: freezes the camera, runs the scanning animation, and
     * lands on the frozen shot with its detection boxes drawn on top. The
     * camera stays paused until the user taps Retake, Discard, or Save.
     * Counts are reset before adding this shot's results (single-shot totals).
     */
    private fun freezeAndAnalyze(bmp: Bitmap) {
        isLiveMode = false
        frozenOverlay.setBackgroundColor(FROZEN_DIM_COLOR)
        frozenOverlay.visibility = View.VISIBLE
        frozenPreviewImage.setImageBitmap(bmp)
        frozenPreviewImage.visibility = View.VISIBLE
        // Tell the overlay the true source bitmap size so boxes line up correctly
        // with the centerCrop-scaled preview image instead of a naive 1:1 mapping.
        overlayView.setImageSize(bmp.width, bmp.height)
        liveScanLabel.text = "● ANALYZING"
        captureBtn.visibility = View.GONE
        retakeBtn.visibility  = View.VISIBLE
        retakeBtn.text        = "Retake"
        discardBtn.visibility = View.VISIBLE
        modeSwitchBtn.text    = "↩ Live Mode"

        startScanAnimation()

        cameraExecutor.execute {
            val results = runDetection(bmp)
            runOnUiThread {
                stopScanAnimation()

                gradeA = 0; gradeB = 0; gradeC = 0; countedBoxes.clear()
                for (det in results) when (det.label) {
                    "Quail_Egg_Grade_A" -> gradeA++
                    "Quail_Egg_Grade_B" -> gradeB++
                    "Quail_Egg_Grade_C" -> gradeC++
                }
                overlayView.setResults(results)
                updateCountUI()
                // Gallery save is deferred until the user confirms Save Collection —
                // stash the shot + its detections instead of writing to disk now.
                singleShotBitmap = bmp
                singleShotResults = results

                val total = gradeA + gradeB + gradeC
                captureBtn.isEnabled = true
                saveBtn.visibility = View.VISIBLE

                liveScanLabel.text = "● CAPTURED"
                showBanner(
                    if (detector != null)
                        "Found $total egg(s) — " +
                                "A:$gradeA Normal · B:$gradeB Cracked · C:$gradeC Reject\n" +
                                "Save, Retake, or Discard."
                    else
                        "Detection disabled — model error. See banner above.",
                    isError = detector == null,
                    autoHide = detector != null
                )
            }
        }
    }

    /**
     * BATCH mode: adds a freshly captured/picked shot to the pending queue.
     * No scanning happens here — camera stays live so the user can keep
     * shooting. Scanning only happens when Done is tapped. [saveToGallery]
     * should only be true for a fresh camera capture — gallery picks already
     * exist on disk and must never be re-saved (that was the duplicate bug).
     */
    private fun addShotToBatchQueue(shot: BatchShot) {
        pendingBatchShots.add(shot)
        // Gallery save is deferred until Save Collection is confirmed — see
        // finishSessionAfterSave. Nothing gets written to disk here.

        // If the camera isn't live (e.g. picking photos before ever starting
        // it, or camera was off), show the shot in the frozen preview instead
        // of leaving a stale/black screen up.
        if (!isLiveMode) {
            frozenOverlay.setBackgroundColor(FROZEN_DIM_COLOR)
            frozenOverlay.visibility = View.VISIBLE
            frozenPreviewImage.setImageBitmap(shot.full)
            frozenPreviewImage.visibility = View.VISIBLE
            // Visual cue that Capture is guarded right now — see onCaptureBtnClicked().
            captureBtn.alpha = 0.4f
        }

        batchShotCount = pendingBatchShots.size
        updateBatchLabel()
        addThumbnailView(pendingBatchShots.size - 1) // O(1) incremental add, not a full rebuild
        doneBatchBtn.visibility = View.VISIBLE
        discardBtn.visibility = View.VISIBLE
        showBanner(
            "Shot ${pendingBatchShots.size} queued — capture more, or tap Done to scan.",
            isError = false, autoHide = true
        )
    }

    /** Downscales a bitmap for the thumbnail strip — keeps the strip smooth
     * even with many queued shots, since we're never rendering full-res
     * camera bitmaps into all those small ImageViews. */
    private fun makeThumb(bmp: Bitmap): Bitmap {
        val maxDim = 160
        val ratio = minOf(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height, 1f)
        val w = (bmp.width * ratio).toInt().coerceAtLeast(1)
        val h = (bmp.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    /** Appends exactly one thumbnail view for pendingBatchShots[index] —
     * used on add so we never re-inflate the whole strip per shot. */
    private fun addThumbnailView(index: Int) {
        if (index !in pendingBatchShots.indices) return
        batchThumbnailScroll.visibility = View.VISIBLE
        val shot = pendingBatchShots[index]
        val item = LayoutInflater.from(this)
            .inflate(R.layout.item_batch_thumbnail, batchThumbnailContainer, false)
        item.findViewById<ImageView>(R.id.thumbImage).apply {
            setImageBitmap(shot.thumb)
            setOnClickListener { showThumbnailInPreview(index) }
        }
        item.findViewById<View>(R.id.thumbDeleteBtn).setOnClickListener {
            if (!isScanningBatch) removeBatchThumbnail(index)
        }
        batchThumbnailContainer.addView(item)
    }

    /** Full rebuild of the strip — only needed after a removal, since indices shift. */
    private fun refreshBatchThumbnails() {
        batchThumbnailContainer.removeAllViews()
        if (pendingBatchShots.isEmpty()) {
            batchThumbnailScroll.visibility = View.GONE
            return
        }
        pendingBatchShots.indices.forEach { addThumbnailView(it) }
    }

    /** Removes a single queued photo (tapped ✕) before scanning has started. */
    private fun removeBatchThumbnail(index: Int) {
        if (isScanningBatch || index !in pendingBatchShots.indices) return
        pendingBatchShots.removeAt(index)
        batchShotCount = pendingBatchShots.size
        updateBatchLabel()
        refreshBatchThumbnails()
        if (pendingBatchShots.isEmpty()) {
            doneBatchBtn.visibility = View.GONE
            showBanner("Batch queue empty", isError = false, autoHide = true)
        }
    }

    /** Tapping a thumbnail shows that photo full-size on the camera screen
     * (like reviewing a frozen shot) without touching the live camera feed
     * underneath — dismissing just hides the overlay again. */
    private fun showThumbnailInPreview(index: Int) {
        if (isScanningBatch || index !in pendingBatchShots.indices) return
        val shot = pendingBatchShots[index]
        isPreviewingThumbnail = true
        isLiveMode = false
        frozenOverlay.setBackgroundColor(FROZEN_DIM_COLOR)
        frozenOverlay.visibility = View.VISIBLE
        frozenPreviewImage.setImageBitmap(shot.full)
        frozenPreviewImage.visibility = View.VISIBLE
        overlayView.setImageSize(shot.full.width, shot.full.height) // Show this shot's boxes if Done has already scanned it; empty (no boxes) if // the user is browsing the queue before scanning.
        overlayView.setResults(shot.results)
        liveScanLabel.text = "● PREVIEW ${index + 1}/${pendingBatchShots.size}"
        captureBtn.visibility = View.GONE
        retakeBtn.visibility  = View.VISIBLE
        retakeBtn.text        = "Back to Camera"
        // discardBtn stays visible/unchanged — the batch can still be discarded from here
    }

    /** Leaves the thumbnail preview and returns to the live batch-capturing view.
     * The camera was never unbound while previewing, so this is instant. */
    private fun dismissThumbnailPreview() {
        isPreviewingThumbnail = false
        // Only claim "live" if the camera was actually bound before entering
        // the preview — the camera is never unbound while previewing, so
        // imageCapture still tells the truth here.
        val cameraActuallyLive = imageCapture != null
        isLiveMode = cameraActuallyLive
        frozenOverlay.visibility = View.GONE
        frozenPreviewImage.visibility = View.GONE
        overlayView.setImageSize(0, 0)
        overlayView.setResults(emptyList())
        updateBatchLabel()
        captureBtn.visibility = View.VISIBLE
        // Dim + guard Capture if the camera isn't really running, same as the
        // gallery-pick-before-camera-start case.
        captureBtn.alpha      = if (cameraActuallyLive) 1f else 0.4f
        retakeBtn.visibility  = View.GONE
        retakeBtn.text        = if (captureMode == CaptureMode.BATCH) "Capture More" else "Retake"
        discardBtn.visibility = if (pendingBatchShots.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * BATCH mode: runs when the user taps Done. This is the ONLY place batch
     * photos get scanned — freezes the camera, scans every queued photo in
     * sequence, and lands on a summary once finished.
     */
    private fun finishBatchAndScan() {
        if (pendingBatchShots.isEmpty()) { toast("No photos queued yet"); return }

        isScanningBatch = true
        isPreviewingThumbnail = false
        isLiveMode = false
        doneBatchBtn.isEnabled = false
        frozenOverlay.setBackgroundColor(FROZEN_DIM_COLOR)
        frozenOverlay.visibility = View.VISIBLE
        frozenPreviewImage.setImageBitmap(pendingBatchShots.last().full)
        frozenPreviewImage.visibility = View.VISIBLE
        captureBtn.visibility = View.GONE
        retakeBtn.visibility  = View.GONE
        discardBtn.visibility = View.VISIBLE
        modeSwitchBtn.text    = "↩ Live Mode"
        liveScanLabel.text    = "● SCANNING (0/${pendingBatchShots.size})"

        gradeA = 0; gradeB = 0; gradeC = 0; countedBoxes.clear()
        overlayView.setResults(emptyList())
        startScanAnimation()

        val queued = pendingBatchShots.map { it.full } // snapshot for the background thread
        cameraExecutor.execute {
            var processed = 0
            queued.forEachIndexed { idx, bmp ->
                val results = runDetection(bmp)
                if (idx < pendingBatchShots.size) pendingBatchShots[idx].results = results

                for (det in results) when (det.label) {
                    "Quail_Egg_Grade_A" -> gradeA++
                    "Quail_Egg_Grade_B" -> gradeB++
                    "Quail_Egg_Grade_C" -> gradeC++
                }
                processed++
                runOnUiThread {
                    frozenPreviewImage.setImageBitmap(bmp)
                    overlayView.setImageSize(bmp.width, bmp.height)
                    overlayView.setResults(results)
                    liveScanLabel.text = "● SCANNING ($processed/${queued.size})"
                    updateCountUI()
                }
            }
            runOnUiThread {
                stopScanAnimation()
                isScanningBatch = false
                val total = gradeA + gradeB + gradeC
                liveScanLabel.text = "● BATCH DONE — $processed photo(s)"
                saveBtn.visibility = View.VISIBLE
                doneBatchBtn.visibility = View.GONE
                doneBatchBtn.isEnabled = true
                showBanner(
                    "Scanned $processed photo(s), $total egg(s) — " +
                            "A:$gradeA Normal · B:$gradeB Cracked · C:$gradeC Reject\n" +
                            "Save to commit, or Discard to cancel.",
                    isError = false, autoHide = true
                )
            }
        }
    }

    /** Discard: throws away everything captured so far (current shot and, in
     * batch mode, the whole queued/running total) and returns to a clean live view. */
    private fun discardCapture() {
        val hadBatch = captureMode == CaptureMode.BATCH &&
                (pendingBatchShots.isNotEmpty() || batchShotCount > 0)
        resumeLive()
        showBanner(
            if (hadBatch) "Batch discarded" else "Discarded",
            isError = false, autoHide = true
        )
        toast("Discarded")
    }

    /** Asks for confirmation before discarding — irreversible, so we don't
     * want a stray tap to wipe out a whole queued batch. */
    private fun confirmDiscard() {
        val hasBatch = captureMode == CaptureMode.BATCH && pendingBatchShots.isNotEmpty()
        val message = if (hasBatch)
            "This will discard all ${pendingBatchShots.size} queued photo(s) and any scanned results. This cannot be undone."
        else
            "This will discard the current capture. This cannot be undone."
        MaterialAlertDialogBuilder(this)
            .setTitle("Discard?")
            .setMessage(message)
            .setPositiveButton("Discard") { _, _ -> discardCapture() }
            .setNegativeButton("Cancel", null)
            .show()
    }
    /** Guards "Pick Photo": while the camera is actively live — either
     * SINGLE mode mid-capture or BATCH mode capturing before Done — picking
     * gallery photos mid-session would collide with the active camera state.
     * Block it until the user has Saved or Discarded, same as the reverse
     * guard on Capture/preview-tap when photos are already queued. */
    private fun onPickPhotoClicked() {
        if (isLiveMode && imageCapture != null) {
            showBanner(
                "Camera is running — Save or Discard the current session before picking photos.",
                isError = true, autoHide = false
            )
            toast("Save or Discard first, then you can pick photos")
            return
        }
        pickMultipleImagesLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }
    /** "↩ Live Mode" discards whatever is currently frozen/queued (a single
     * shot's count, or the whole batch preview) via resumeLive() — confirm
     * first so a stray tap doesn't silently wipe out unsaved work. */
    private fun confirmLiveModeSwitch() {
        val hasBatch = captureMode == CaptureMode.BATCH && pendingBatchShots.isNotEmpty()
        val hasSingle = captureMode == CaptureMode.SINGLE && singleShotBitmap != null
        if (!hasBatch && !hasSingle) {
            // Nothing to lose — just switch back.
            resumeLive()
            return
        }
        val message = if (hasBatch)
            "This will discard all ${pendingBatchShots.size} queued photo(s) and any scanned results. This cannot be undone."
        else
            "This will discard the current photo and its egg count. This cannot be undone."
        MaterialAlertDialogBuilder(this)
            .setTitle("Switch to Live Mode?")
            .setMessage(message)
            .setPositiveButton("Switch") { _, _ -> resumeLive() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Full reset: discards the current shot (Single) or the whole in-progress batch (Batch). */
    private fun resumeLive() {
        isLiveMode = true
        isPreviewingThumbnail = false
        frozenOverlay.visibility = View.GONE
        frozenPreviewImage.visibility = View.GONE
        overlayView.setImageSize(0, 0) // back to naive 1:1 mapping for the live camera feed
        stopScanAnimation()
        overlayView.setResults(emptyList())
        captureBtn.visibility = View.VISIBLE
        captureBtn.isEnabled  = true
        captureBtn.alpha      = 1f
        retakeBtn.visibility  = View.GONE
        discardBtn.visibility = View.GONE
        modeSwitchBtn.text    = "Pick Photo"
        saveBtn.visibility    = View.GONE
        batchShotCount = 0
        resetCounts()
        updateBatchLabel()
        pendingBatchShots.clear()
        refreshBatchThumbnails()
        doneBatchBtn.visibility = View.GONE
        isScanningBatch = false
        // Discard any shot staged for gallery save — nothing gets written now.
        singleShotBitmap = null
        singleShotResults = emptyList()
    }

    /** SINGLE mode only: retaking throws away the current frozen shot and its
     * count — confirm first since it can't be undone once the user moves on. */
    private fun confirmRetake() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Retake?")
            .setMessage("This will discard the current photo and its egg count. This cannot be undone.")
            .setPositiveButton("Retake") { _, _ -> retakeSingleShot() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Clears the stashed shot and resets counts, then returns to the live feed. */
    private fun retakeSingleShot() {
        singleShotBitmap = null
        singleShotResults = emptyList()
        resetCounts()
        saveBtn.visibility = View.GONE
        resumeLiveKeepCounts()
    }

    /** Used after a successful SINGLE-mode shot — returns to live preview.
     * (Batch mode no longer routes through here since captures just queue.) */
    private fun resumeLiveKeepCounts() {
        isLiveMode = true
        frozenOverlay.visibility = View.GONE
        frozenPreviewImage.visibility = View.GONE
        overlayView.setImageSize(0, 0) // back to naive 1:1 mapping for the live camera feed
        overlayView.setResults(emptyList())
        updateBatchLabel()
        captureBtn.visibility = View.VISIBLE
        captureBtn.isEnabled  = true
        retakeBtn.visibility  = View.GONE
        discardBtn.visibility = View.GONE
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Multi-select "Pick Photo" — queues gallery picks into the same batch
    //  queue as camera captures. Nothing is scanned until Done is tapped, and
    //  picked photos are NEVER re-saved to the gallery (they're already there).
    // ─────────────────────────────────────────────────────────────────────────

    private fun queuePickedPhotos(uris: List<Uri>) {
        showBanner("Loading ${uris.size} photo(s)…", isError = false, autoHide = true)
        cameraExecutor.execute {
            val shots = uris.mapNotNull { uri ->
                uriToBitmap(uri)?.let { bmp -> BatchShot(bmp, makeThumb(bmp), fromCamera = false) }
            }
            runOnUiThread {
                shots.forEach { addShotToBatchQueue(it) }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Scanning animation (played while YOLO is analyzing a frozen/picked photo)
    // ─────────────────────────────────────────────────────────────────────────

    private fun startScanAnimation() {
        scanLineView.visibility = View.VISIBLE
        scanLineView.translationY = 0f
        scanLineView.post {
            val parent = scanLineView.parent as? View
            val parentHeight = parent?.height?.toFloat() ?: 0f
            scanAnimator?.cancel()
            scanAnimator = ObjectAnimator.ofFloat(
                scanLineView, View.TRANSLATION_Y, 0f, parentHeight
            ).apply {
                duration = 1200
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                start()
            }
        }
    }

    private fun stopScanAnimation() {
        scanAnimator?.cancel()
        scanAnimator = null
        scanLineView.visibility = View.GONE
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Firebase: Save today's collection to Realtime Database
    // ─────────────────────────────────────────────────────────────────────────

    /** Asks for confirmation before committing to Firebase. */
    private fun confirmSaveCollection() {
        val total = gradeA + gradeB + gradeC
        if (total == 0) {
            toast("No eggs detected yet — nothing to save.")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Save Collection?")
            .setMessage(
                "This will add $total egg(s) to today's collection " +
                        "(A:$gradeA Normal · B:$gradeB Cracked · C:$gradeC Reject)."
            )
            .setPositiveButton("Save") { _, _ -> saveCollectionToDatabase() }
            .setNegativeButton("Cancel", null)
            .show()
    }

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

            val updatedRecord = mapOf(
                "date"          to today,
                "total"         to updatedTotal,
                "gradeA"        to updatedA,
                "gradeB"        to updatedB,
                "gradeC"        to updatedC,
                "timestamp"     to System.currentTimeMillis(),
                "savedBy"       to (auth.currentUser?.uid ?: "unknown")
            )

            ref.setValue(updatedRecord)
                .addOnSuccessListener {
                    toast("Collection updated!")
                    finishSessionAfterSave(updatedTotal, updatedA, updatedB, updatedC)
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

    /** After a successful save: clear the batch queue, reset counts, and turn
     * the camera fully off so the next session starts from a clean slate. */
    private fun finishSessionAfterSave(total: Int, a: Int, b: Int, c: Int) {
        saveBtn.text = getString(R.string.save_collection)
        saveBtn.isEnabled = true

        // Only now — after the user confirmed Save Collection — do we actually
        // write photos to the gallery, with detection boxes baked in. Gallery
        // picks (fromCamera == false) are skipped since they're already on disk.
        val singleToSave = singleShotBitmap?.let { it to singleShotResults }
        val batchToSave = pendingBatchShots.filter { it.fromCamera }.map { it.full to it.results }
        singleShotBitmap = null
        singleShotResults = emptyList()

        cameraExecutor.execute {
            singleToSave?.let { (bmp, results) ->
                trySaveToGallery(drawDetectionsOnBitmap(bmp, results))
            }
            batchToSave.forEach { (bmp, results) ->
                trySaveToGallery(drawDetectionsOnBitmap(bmp, results))
            }
        }

        pendingBatchShots.clear()
        refreshBatchThumbnails()
        doneBatchBtn.visibility = View.GONE
        isPreviewingThumbnail = false
        stopCameraCore()
        showBanner(
            "✓ Saved $total eggs — A:$a Normal · B:$b Cracked · C:$c Reject. " +
                    "Camera turned off — tap preview to start a new session.",
            isError = false, autoHide = true
        )
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

            val item = inflater.inflate(R.layout.item_collection_log, container, false)
            item.findViewById<TextView>(R.id.logDate).text  = fullDateText
            item.findViewById<TextView>(R.id.logTotal).text = total.toString()

            item.findViewById<TextView>(R.id.logGradeA).text           = "$gA"
            item.findViewById<TextView>(R.id.logGradeALabel)?.text     = "Normal"

            item.findViewById<TextView>(R.id.logGradeB).text           = "$gB"
            item.findViewById<TextView>(R.id.logGradeBLabel)?.text     = "Cracked"

            item.findViewById<TextView>(R.id.logGradeC).text           = "$gC"
            item.findViewById<TextView>(R.id.logGradeCLabel)?.text     = "Reject"

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

        findViewById<TextView>(R.id.todayTotalValue).text = total.toString()

        // Grade A — Normal (full price)
        findViewById<TextView>(R.id.gradeAValue).text  = gradeA.toString()
        findViewById<TextView>(R.id.gradeADesc)?.text  = "Normal"

        // Grade B — Cracked (half price)
        findViewById<TextView>(R.id.gradeBValue).text  = gradeB.toString()
        findViewById<TextView>(R.id.gradeBDesc)?.text  = "Cracked"

        // Grade C — Reject
        findViewById<TextView>(R.id.gradeCValue).text  = gradeC.toString()
        findViewById<TextView>(R.id.gradeCDesc)?.text  = "Reject"

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
        saveBtn.visibility = View.GONE
        discardBtn.visibility = View.GONE
        captureModeToggleBtn.text =
            if (captureMode == CaptureMode.BATCH) "Mode: Batch" else "Mode: Single"
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
        handler.post(timeUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        historyListener?.let { historyRef?.removeEventListener(it) }
        historyListener = null
        handler.removeCallbacks(timeUpdateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        detector?.close()
        cameraProvider?.unbindAll()
        historyListener?.let { historyRef?.removeEventListener(it) }
    }

    companion object {
        private const val TAG       = "EggCountActivity"
        private const val IOU_DEDUP = 0.4f
        private const val MAX_BATCH_PICK = 20
        private val FROZEN_DIM_COLOR = Color.parseColor("#55000000")
    }
}