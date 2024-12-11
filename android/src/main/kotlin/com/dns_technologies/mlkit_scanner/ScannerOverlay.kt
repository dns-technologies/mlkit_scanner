package com.dns_technologies.mlkit_scanner

import android.content.Context
import android.graphics.*
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
        strokeCap = Paint.Cap.ROUND
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
            createBorderPath(width, height)
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
        val cornerLineLength = width * 0.05F
        val radius = cornerLineLength / 2
        val topLeftArcRect = RectF(x, y, x + radius, y + radius)
        val topRightArcRect = RectF(x + width - radius, y, x + width, y + radius)
        val bottomRightArcRect =  RectF(x + width - radius, y + height - radius, x + width, y + height)
        val bottomLeftArcRect = RectF(x, y + height - radius, x + radius, y + height)
        borderPath = Path().apply {
            // Top Left Corner
            roundCorner(
                from = PointF(x, y + cornerLineLength),
                to = PointF(x + cornerLineLength, y),
                startAngle = -180F,
                roundRect = topLeftArcRect
            )

            // Top Right Corner
            roundCorner(
                from = PointF(x + width - cornerLineLength, y),
                to = PointF(x + width, y + cornerLineLength),
                startAngle = -90F,
                roundRect = topRightArcRect
            )

            // Bottom Right Corner
            roundCorner(
                from = PointF(x + width, y + height - cornerLineLength),
                to = PointF(x + width - cornerLineLength, y + height),
                startAngle = 0F,
                roundRect = bottomRightArcRect
            )

            // Bottom Left Corner
            roundCorner(
                from = PointF(x + cornerLineLength, y + height),
                to = PointF(x, y + height - cornerLineLength),
                startAngle = 90F,
                roundRect = bottomLeftArcRect
            )
        }
        backgroundPath = Path().apply {
            moveTo(0F, 0F)
            lineTo(x, y)
            arcTo(topLeftArcRect, -180F, 90F)
            lineTo(x + width - radius, y)
            arcTo(topRightArcRect, -90F, 90F)
            lineTo(x + width, height + y - radius)
            arcTo(bottomRightArcRect, 0F, 90F)
            lineTo(x + width, y + height)
            lineTo(w.toFloat(), h.toFloat())
            lineTo(w.toFloat(), 0F)
            lineTo(0F, 0F)
            lineTo(x, y)
            lineTo(x, y + height - radius)
            arcTo(bottomLeftArcRect, -180F, -90F)
            lineTo(x + width, height + y)
            lineTo(w.toFloat(), h.toFloat())
            lineTo(0F, h.toFloat())
            close()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.apply {
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

private fun Path.roundCorner(from: PointF, to: PointF, startAngle: Float, roundRect: RectF) {
    moveTo(from.x, from.y)
    arcTo(roundRect, startAngle, 90F)
    lineTo(to.x, to.y)
}