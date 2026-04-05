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
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val pixels  = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

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
        val raw = outputMap[0].value as Array<Array<FloatArray>>

        val candidates = mutableListOf<DetectionResult>()
        for (i in 0 until 8400) {
            val cx = raw[0][0][i]
            val cy = raw[0][1][i]
            val w  = raw[0][2][i]
            val h  = raw[0][3][i]

            var maxScore = 0f
            var maxClass = 0
            for (c in LABELS.indices) {
                val score = raw[0][4 + c][i]
                if (score > maxScore) { maxScore = score; maxClass = c }
            }

            if (maxScore >= CONFIDENCE_THRESHOLD) {
                candidates.add(DetectionResult(
                    label       = LABELS[maxClass],
                    confidence  = maxScore,
                    boundingBox = RectF(
                        (cx - w / 2f) / INPUT_SIZE,
                        (cy - h / 2f) / INPUT_SIZE,
                        (cx + w / 2f) / INPUT_SIZE,
                        (cy + h / 2f) / INPUT_SIZE
                    )
                ))
            }
        }

        inputTensor.close()
        outputMap.close()
        return nms(candidates, NMS_IOU_THRESHOLD)
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