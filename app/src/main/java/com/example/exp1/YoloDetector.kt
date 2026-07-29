package com.example.exp1

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import java.nio.FloatBuffer

data class DetectionResult(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF
)

class YoloDetector(context: Context) {

    companion object {
        private const val MODEL_FILE           = "my_model.onnx"
        private const val INPUT_SIZE           = 640
        private const val CONFIDENCE_THRESHOLD = 0.40f
        private const val NMS_IOU_THRESHOLD    = 0.45f
        private const val TILE_TRIGGER_SIZE    = 900   // above this, tile instead of one big resize
        private const val TILE_OVERLAP_RATIO   = 0.2f
        val LABELS = listOf("Quail_Egg_Grade_A", "Quail_Egg_Grade_B", "Quail_Egg_Grade_C")
    }

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession

    init {
        val modelBytes = context.assets.open(MODEL_FILE).readBytes()
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(2)
        opts.setInterOpNumThreads(1)
        ortSession = ortEnv.createSession(modelBytes, opts)
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        val srcW = bitmap.width.toFloat()
        val srcH = bitmap.height.toFloat()
        val scale = minOf(INPUT_SIZE / srcW, INPUT_SIZE / srcH)
        val newW = (srcW * scale).toInt()
        val newH = (srcH * scale).toInt()
        val padX = (INPUT_SIZE - newW) / 2f
        val padY = (INPUT_SIZE - newH) / 2f

        val scaledBmp = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        val letterboxed = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(letterboxed).apply {
            drawColor(android.graphics.Color.rgb(114, 114, 114))
            drawBitmap(scaledBmp, padX, padY, null)
        }

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        letterboxed.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val rCh = FloatArray(INPUT_SIZE * INPUT_SIZE)
        val gCh = FloatArray(INPUT_SIZE * INPUT_SIZE)
        val bCh = FloatArray(INPUT_SIZE * INPUT_SIZE)
        for (i in pixels.indices) {
            rCh[i] = ((pixels[i] shr 16) and 0xFF) / 255f
            gCh[i] = ((pixels[i] shr  8) and 0xFF) / 255f
            bCh[i] = ( pixels[i]         and 0xFF) / 255f
        }

        val buf = FloatBuffer.allocate(3 * INPUT_SIZE * INPUT_SIZE)
        buf.put(rCh); buf.put(gCh); buf.put(bCh)
        buf.rewind()

        val shape       = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val inputTensor = OnnxTensor.createTensor(ortEnv, buf, shape)
        val inputName   = ortSession.inputNames.iterator().next()

        val inputMap = HashMap<String, OnnxTensor>()
        inputMap[inputName] = inputTensor

        val outputMap = ortSession.run(inputMap)

        // This model is an Ultralytics "end2end" (YOLO26-style) export: it performs
        // NMS internally and returns a fixed [1, 300, 6] tensor, where each row is
        // [x1, y1, x2, y2, confidence, class_id] in pixel coordinates of the
        // INPUT_SIZE x INPUT_SIZE resized image. This is NOT the old raw
        // [1, 4+numClasses, 8400] anchor-grid format, so no further NMS is needed.
        val raw = outputMap[0].value as Array<Array<FloatArray>>
        val detections = raw[0] // shape: [300][6]

        val candidates = mutableListOf<DetectionResult>()
        for (det in detections) {
            val x1         = det[0]
            val y1         = det[1]
            val x2         = det[2]
            val y2         = det[3]
            val confidence = det[4]
            val classId    = det[5].toInt()

            if (confidence >= CONFIDENCE_THRESHOLD && classId in LABELS.indices) {
                val ox1 = (x1 - padX) / scale
                val oy1 = (y1 - padY) / scale
                val ox2 = (x2 - padX) / scale
                val oy2 = (y2 - padY) / scale

                candidates.add(DetectionResult(
                    label       = LABELS[classId],
                    confidence  = confidence,
                    boundingBox = RectF(
                        (ox1 / srcW).coerceIn(0f, 1f),
                        (oy1 / srcH).coerceIn(0f, 1f),
                        (ox2 / srcW).coerceIn(0f, 1f),
                        (oy2 / srcH).coerceIn(0f, 1f)
                    )
                ))
            }
        }

        inputTensor.close()
        outputMap.close()
        return dedupeOverlaps(candidates, NMS_IOU_THRESHOLD)
    }

    /**
     * The model's internal NMS is per-class, so a single physical egg can still
     * produce two overlapping boxes with different grade labels (e.g. Grade A
     * and Grade B) that both survive because they're technically different
     * classes. This pass removes those cross-class duplicates, keeping only
     * the highest-confidence box per physical egg.
     */
    private fun dedupeOverlaps(dets: List<DetectionResult>, iouThr: Float): List<DetectionResult> {
        val sorted = dets.sortedByDescending { it.confidence }.toMutableList()
        val kept = mutableListOf<DetectionResult>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)
            sorted.removeAll { iou(best.boundingBox, it.boundingBox) > iouThr }
        }
        return kept
    }

    /**
     * For captured/still photos: instead of squeezing the entire photo into a
     * single 640x640 input (which shrinks each individual egg to a handful of
     * pixels when the tray/photo is large), split the photo into overlapping
     * 640x640 tiles at native resolution, detect on each tile, map results back
     * to full-image coordinates, then remove duplicates found in the overlap
     * regions. Falls back to a single plain detect() for small images, where
     * tiling would add cost with no benefit.
     */
    fun detectFullImage(bitmap: Bitmap): List<DetectionResult> {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= TILE_TRIGGER_SIZE && h <= TILE_TRIGGER_SIZE) {
            return detect(bitmap)
        }

        val tileSize = INPUT_SIZE
        val stride = (tileSize * (1f - TILE_OVERLAP_RATIO)).toInt()
        val xs = tileOffsets(w, tileSize, stride)
        val ys = tileOffsets(h, tileSize, stride)

        val allDetections = mutableListOf<DetectionResult>()
        for (ty in ys) {
            for (tx in xs) {
                val tileW = minOf(tileSize, w - tx)
                val tileH = minOf(tileSize, h - ty)
                val tileBmp = Bitmap.createBitmap(bitmap, tx, ty, tileW, tileH)
                val tileDetections = detect(tileBmp)

                for (d in tileDetections) {
                    val gx1 = (tx + d.boundingBox.left * tileW) / w
                    val gy1 = (ty + d.boundingBox.top * tileH) / h
                    val gx2 = (tx + d.boundingBox.right * tileW) / w
                    val gy2 = (ty + d.boundingBox.bottom * tileH) / h
                    allDetections.add(
                        DetectionResult(
                            label       = d.label,
                            confidence  = d.confidence,
                            boundingBox = RectF(gx1, gy1, gx2, gy2)
                        )
                    )
                }
            }
        }

        return dedupeOverlaps(allDetections, NMS_IOU_THRESHOLD)
    }

    private fun tileOffsets(total: Int, tile: Int, stride: Int): List<Int> {
        if (total <= tile) return listOf(0)
        val offsets = mutableListOf<Int>()
        var pos = 0
        while (pos + tile < total) {
            offsets.add(pos)
            pos += stride
        }
        offsets.add(total - tile) // flush final tile against the far edge
        return offsets.distinct()
    }

    private fun nms(dets: List<DetectionResult>, iouThr: Float): List<DetectionResult> {
        val kept = mutableListOf<DetectionResult>()
        for (label in LABELS) {
            val cls = dets.filter { it.label == label }
                .sortedByDescending { it.confidence }
                .toMutableList()
            while (cls.isNotEmpty()) {
                val best = cls.removeAt(0)
                kept.add(best)
                cls.removeAll { iou(best.boundingBox, it.boundingBox) > iouThr }
            }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val il = maxOf(a.left, b.left)
        val it = maxOf(a.top, b.top)
        val ir = minOf(a.right, b.right)
        val ib = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, ir - il) * maxOf(0f, ib - it)
        if (inter == 0f) return 0f
        return inter / (
                (a.right - a.left) * (a.bottom - a.top) +
                        (b.right - b.left) * (b.bottom - b.top) - inter
                )
    }

    fun close() {
        runCatching { ortSession.close() }
        runCatching { ortEnv.close() }
    }
}