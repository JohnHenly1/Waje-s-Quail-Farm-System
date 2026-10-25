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

    // Size of the bitmap the normalized boxes were computed against.
    // 0/0 = unknown -> naive 1:1 mapping.
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0

    private val colorMap = mapOf(
        "Quail_Egg_Grade_A" to Color.parseColor("#4CAF50"),
        "Quail_Egg_Grade_B" to Color.parseColor("#FF9800"),
        "Quail_Egg_Grade_C" to Color.parseColor("#F44336")
    )

    private fun shortLabel(label: String): String = when {
        label.endsWith("_A", ignoreCase = true) -> "A"
        label.endsWith("_B", ignoreCase = true) -> "B"
        label.endsWith("_C", ignoreCase = true) -> "C"
        else -> label
    }

    fun setImageSize(width: Int, height: Int) {
        sourceWidth = width
        sourceHeight = height
    }

    fun setResults(detections: List<DetectionResult>) {
        results = detections
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        var scale = 1f
        var offsetX = 0f
        var offsetY = 0f
        val useCenterCrop = sourceWidth > 0 && sourceHeight > 0
        if (useCenterCrop) {
            scale = maxOf(
                width.toFloat() / sourceWidth,
                height.toFloat() / sourceHeight
            )
            offsetX = (width - sourceWidth * scale) / 2f
            offsetY = (height - sourceHeight * scale) / 2f
        }

        for (det in results) {
            val color = colorMap[det.label] ?: Color.WHITE

            val left: Float; val top: Float; val right: Float; val bottom: Float
            if (useCenterCrop) {
                left   = det.boundingBox.left   * sourceWidth  * scale + offsetX
                top    = det.boundingBox.top    * sourceHeight * scale + offsetY
                right  = det.boundingBox.right  * sourceWidth  * scale + offsetX
                bottom = det.boundingBox.bottom * sourceHeight * scale + offsetY
            } else {
                left   = det.boundingBox.left   * width
                top    = det.boundingBox.top    * height
                right  = det.boundingBox.right  * width
                bottom = det.boundingBox.bottom * height
            }

            boxPaint.color = color
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Letter only: A / B / C, no percentage
            val label = shortLabel(det.label)
            val textW = textPaint.measureText(label)
            val chipH = 36f
            val chipTop = (top - chipH).coerceAtLeast(0f)
            bgPaint.color = color
            canvas.drawRect(left, chipTop, left + textW + 16f, chipTop + chipH, bgPaint)
            textPaint.color = Color.WHITE
            canvas.drawText(label, left + 8f, chipTop + chipH - 8f, textPaint)
        }
    }
}