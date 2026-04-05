package com.example.exp1

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class DetectionOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        textSize = 32f
        isFakeBoldText = true
        isAntiAlias = true
    }
    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private var results: List<DetectionResult> = emptyList()

    private val colorMap = mapOf(
        "Quail_Egg_Grade_A" to Color.parseColor("#4CAF50"),
        "Quail_Egg_Grade_B" to Color.parseColor("#FF9800"),
        "Quail_Egg_Grade_C" to Color.parseColor("#F44336")
    )

    fun setResults(detections: List<DetectionResult>) {
        results = detections
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (det in results) {
            val color = colorMap[det.label] ?: Color.WHITE
            val left   = det.boundingBox.left   * width
            val top    = det.boundingBox.top    * height
            val right  = det.boundingBox.right  * width
            val bottom = det.boundingBox.bottom * height

            boxPaint.color = color
            canvas.drawRect(left, top, right, bottom, boxPaint)

            val label = "${det.label} ${"%.0f".format(det.confidence * 100)}%"
            val textW = textPaint.measureText(label)
            bgPaint.color = color
            canvas.drawRect(left, top - 36f, left + textW + 8f, top, bgPaint)
            textPaint.color = Color.WHITE
            canvas.drawText(label, left + 4f, top - 8f, textPaint)
        }
    }
}