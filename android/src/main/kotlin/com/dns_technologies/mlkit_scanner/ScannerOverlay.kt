package com.dns_technologies.mlkit_scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.dns_technologies.mlkit_scanner.models.RecognizeVisorCropRect

/**
 * Standard scanner overlay
 *
 * Draw with [RecognizeVisorCropRect]
 **/
class ScannerOverlay(
    private var cropArea: RecognizeVisorCropRect, context: Context
) : View(context) {

    private var borderPath = Path()
    private var backgroundPath = Path()
    private val cornerPaint = Paint().apply {
        strokeWidth = 6F
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }
    private val backgroundColor = Paint().apply {
        color = Color.BLACK
        alpha = 120
        style = Paint.Style.FILL
    }
    var isActive = false
        set(value) {
            field = value
            invalidate()
        }

    var cropRect: RecognizeVisorCropRect
        get() = cropArea
        set(value) {
            cropArea = value
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        createBorderPath(w, h)
        invalidate()
    }

    private fun createBorderPath(w: Int, h: Int) {
        val width = w * cropArea.scaleWidth.toFloat()
        val height = h * cropArea.scaleHeight.toFloat()
        val x = (w / 2 * (1 + cropArea.centerOffsetX) - width / 2).toFloat()
        val y = (h / 2 * (1 + cropArea.centerOffsetY) - height / 2).toFloat()
        val cornerLineLength = width * 0.10F
        borderPath = Path().apply {
            moveTo(x, y + cornerLineLength)
            lineTo(x, y)
            lineTo(x + cornerLineLength, y)

            // Top Right Corner
            moveTo(x + width - cornerLineLength, y)
            lineTo(x + width, y);
            lineTo(x + width, y + cornerLineLength)

            // Bottom Right Corner
            moveTo(x + width, y + height - cornerLineLength)
            lineTo(x + width, y + height);
            lineTo(x + width - cornerLineLength, y + height)

            // Bottom Left Corner
            moveTo(x + cornerLineLength, y + height)
            lineTo(x, y + height);
            lineTo(x, y + height - cornerLineLength)
        }
        backgroundPath = Path().apply {
            moveTo(0F, 0F)
            lineTo(x, y)
            lineTo(x + width, y)
            lineTo(x + width, height + y)
            lineTo(w.toFloat(), h.toFloat())
            lineTo(w.toFloat(), 0F)
            lineTo(0F, 0F)
            lineTo(x, y)
            lineTo(x, height + y)
            lineTo(x + width, height + y)
            lineTo(w.toFloat(), h.toFloat())
            lineTo(0F, h.toFloat())
            close()
        }
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        canvas?.apply {
            drawPath(backgroundPath, backgroundColor)
            cornerPaint.color = getBorderColor()
            drawPath(borderPath, cornerPaint)
        }
    }

    private fun getBorderColor(): Int {
        return when (isActive) {
            true -> Color.parseColor("#43A047")
            else -> Color.parseColor("#616161")
        }
    }
}