package com.dns_technologies.mlkit_scanner.scanner.components.ui.visor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import androidx.core.graphics.toColorInt

/** Draws the scanner visor overlay for the configured recognition rectangle. */
class VisorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private var cropArea = RecognizeVisorCropRect()

    /** Creates a visor view with an initial recognition rectangle. */
    constructor(cropArea: RecognizeVisorCropRect, context: Context) : this(context) {
        this.cropArea = cropArea
    }

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

    /** Controls whether the visor is rendered in active scan state. */
    var isActive = false
        set(value) {
            field = value
            invalidate()
        }

    /** Recognition rectangle used to draw the visible visor area. */
    var cropRect: RecognizeVisorCropRect
        get() = cropArea
        set(value) {
            cropArea = value
            createBorderPath(width, height)
            invalidate()
        }

    /** Rebuilds visor paths after the view size changes. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        createBorderPath(w, h)
        invalidate()
    }

    /** Rebuilds paths used to draw the visor border and dimmed background. */
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

    /** Draws the dimmed background and scanner recognition border. */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.apply {
            drawPath(backgroundPath, backgroundColor)
            cornerPaint.color = getBorderColor()
            drawPath(borderPath, cornerPaint)
        }
    }

    /** Returns the border color that matches the current scanner activity state. */
    private fun getBorderColor(): Int {
        return when (isActive) {
            true -> "#43A047".toColorInt()
            else -> "#616161".toColorInt()
        }
    }
}

/** Adds one rounded visor corner segment to this path. */
private fun Path.roundCorner(from: PointF, to: PointF, startAngle: Float, roundRect: RectF) {
    moveTo(from.x, from.y)
    arcTo(roundRect, startAngle, 90F)
    lineTo(to.x, to.y)
}
