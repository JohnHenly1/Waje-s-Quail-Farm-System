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

class YoloDetector @JvmOverloads constructor(context: Context, val modelType: ModelType = ModelType.MY_MODEL) {

    /** The two interchangeable detector backends. Each ships a different ONNX
     *  export format, so [detect] dispatches to a different output parser
     *  depending on which one is active — see [OutputFormat]. */
    enum class ModelType(
        val fileName: String,
        val displayName: String,
        val outputFormat: OutputFormat
    ) {
        MY_MODEL("my_model.onnx", "YOLOv3 (my_model)", OutputFormat.END2END_NMS),
        YOLOV4("Yolov4.onnx", "YOLOv4", OutputFormat.RAW_GRID)
    }

    /** END2END_NMS: Ultralytics "end2end" export — NMS already applied inside
     *  the graph, fixed [1, 300, 6] output of [x1,y1,x2,y2,conf,classId] boxes
     *  in pixel coordinates of the resized input.
     *
     *  RAW_GRID: plain detection head export with no built-in NMS, output
     *  [1, 4+numClasses, numAnchors] (channels-first): rows 0-3 are
     *  [cx,cy,w,h] in pixel coordinates of the resized input, rows 4..N are
     *  per-class sigmoid scores (no separate objectness column). Needs
     *  decoding + NMS on-device, which [detect] handles via [dedupeOverlaps]. */
    enum class OutputFormat { END2END_NMS, RAW_GRID }

    companion object {
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
        val modelBytes = context.assets.open(modelType.fileName).readBytes()
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

        val candidates = when (modelType.outputFormat) {
            OutputFormat.END2END_NMS -> parseEnd2EndOutput(outputMap, padX, padY, scale, srcW, srcH)
            OutputFormat.RAW_GRID    -> parseRawGridOutput(outputMap, padX, padY, scale, srcW, srcH)
        }

        inputTensor.close()
        outputMap.close()
        // END2END_NMS already ran per-class NMS internally, so this pass only
        // mops up leftover cross-class duplicates. RAW_GRID has had no NMS
        // applied yet at this point, so this pass is doing the full job.
        return dedupeOverlaps(candidates, NMS_IOU_THRESHOLD)
    }

    /** Ultralytics "end2end" (YOLO26-style) export: NMS performed internally,
     *  fixed [1, 300, 6] tensor of [x1,y1,x2,y2,confidence,classId] rows in
     *  pixel coordinates of the INPUT_SIZE x INPUT_SIZE resized image. */
    private fun parseEnd2EndOutput(
        outputMap: OrtSession.Result,
        padX: Float, padY: Float, scale: Float, srcW: Float, srcH: Float
    ): List<DetectionResult> {
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
        return candidates
    }

    /** Plain detection-head export (e.g. this project's Yolov4.onnx) with no
     *  built-in NMS: channels-first [1, 4+numClasses, numAnchors] tensor.
     *  Rows 0-3 are [cx,cy,w,h] in pixel coordinates of the resized input;
     *  rows 4..N are per-class sigmoid scores (no separate objectness row). */
    private fun parseRawGridOutput(
        outputMap: OrtSession.Result,
        padX: Float, padY: Float, scale: Float, srcW: Float, srcH: Float
    ): List<DetectionResult> {
        val raw      = outputMap[0].value as Array<Array<FloatArray>>
        val channels = raw[0]                 // shape: [4+numClasses][numAnchors]
        val numAnchors = channels[0].size
        val numClasses = channels.size - 4

        val candidates = mutableListOf<DetectionResult>()
        for (i in 0 until numAnchors) {
            var bestClass = -1
            var bestScore = 0f
            for (c in 0 until numClasses) {
                val score = channels[4 + c][i]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }
            if (bestScore < CONFIDENCE_THRESHOLD || bestClass !in LABELS.indices) continue

            val cx = channels[0][i]
            val cy = channels[1][i]
            val w  = channels[2][i]
            val h  = channels[3][i]

            val x1 = cx - w / 2f
            val y1 = cy - h / 2f
            val x2 = cx + w / 2f
            val y2 = cy + h / 2f

            val ox1 = (x1 - padX) / scale
            val oy1 = (y1 - padY) / scale
            val ox2 = (x2 - padX) / scale
            val oy2 = (y2 - padY) / scale

            candidates.add(DetectionResult(
                label       = LABELS[bestClass],
                confidence  = bestScore,
                boundingBox = RectF(
                    (ox1 / srcW).coerceIn(0f, 1f),
                    (oy1 / srcH).coerceIn(0f, 1f),
                    (ox2 / srcW).coerceIn(0f, 1f),
                    (oy2 / srcH).coerceIn(0f, 1f)
                )
            ))
        }
        return candidates
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