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

    // Source frame size (the bitmap the normalized boxes were computed against).
    // 0/0 = unknown -> fall back to naive 1:1 mapping (used for the live camera feed,
    // where PreviewView's own centerCrop-equivalent scaling already roughly matches).
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0

    private val colorMap = mapOf(
        "Quail_Egg_Grade_A" to Color.parseColor("#4CAF50"),
        "Quail_Egg_Grade_B" to Color.parseColor("#FF9800"),
        "Quail_Egg_Grade_C" to Color.parseColor("#F44336")
    )

    /** Call this BEFORE setResults() whenever showing a static bitmap (captured photo or
     *  gallery pick) whose ImageView uses centerCrop, so boxes line up with the visible crop.
     *  Pass (0, 0) to clear it and go back to naive 1:1 mapping (e.g. for live camera). */
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

        // Precompute the centerCrop transform (matches ImageView's scaleType="centerCrop")
        // when we know the source bitmap size.
        var scale = 1f
        var offsetX = 0f
        var offsetY = 0f
        val useCenterCrop = sourceWidth > 0 && sourceHeight > 0
        if (useCenterCrop) {
            scale = maxOf(
                width.toFloat() / sourceWidth,
                height.toFloat() / sourceHeight
            )
            val scaledW = sourceWidth * scale
            val scaledH = sourceHeight * scale
            offsetX = (width - scaledW) / 2f
            offsetY = (height - scaledH) / 2f
        }

        for (det in results) {
            val color = colorMap[det.label] ?: Color.WHITE

            val left: Float; val top: Float; val right: Float; val bottom: Float
            if (useCenterCrop) {
                // boundingBox is normalized [0,1] against the ORIGINAL bitmap.
                // Convert to bitmap pixels, then apply the same scale+offset the
                // ImageView used to centerCrop that bitmap into this view.
                left   = det.boundingBox.left   * sourceWidth  * scale + offsetX
                top    = det.boundingBox.top    * sourceHeight * scale + offsetY
                right  = det.boundingBox.right  * sourceWidth  * scale + offsetX
                bottom = det.boundingBox.bottom * sourceHeight * scale + offsetY
            } else {
                // Naive 1:1 mapping (live camera feed).
                left   = det.boundingBox.left   * width
                top    = det.boundingBox.top    * height
                right  = det.boundingBox.right  * width
                bottom = det.boundingBox.bottom * height
            }

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