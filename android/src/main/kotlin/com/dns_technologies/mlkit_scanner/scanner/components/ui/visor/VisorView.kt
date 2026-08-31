package com.dns_technologies.mlkit_scanner.scanner.components.ui.visor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import kotlin.math.roundToInt

/**
 * Draws the scanner visor overlay for the configured recognition rectangle.
 *
 * @param context Android context used to create the overlay.
 * @param attrs Optional XML attributes when inflated by Android.
 * @param defStyleAttr Default style attribute applied by Android view inflation.
 */
class VisorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private var cropArea: RecognizeVisorCropRect? = null
    private var borderPath = Path()
    private var backgroundPath = Path()
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = BORDER_WIDTH_DP * resources.displayMetrics.density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val backgroundColor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 120
        style = Paint.Style.FILL
    }

    /** Controls whether the visor is rendered in active scan state. */
    var isActive = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Last visor rectangle rendered by this view. */
    internal var cropBounds: Rect? = null
        private set

    /** Reports geometry resolved from this view's actual laid-out size. */
    internal var onCropBoundsChanged: ((Rect, Int, Int) -> Unit)? = null

    /** Retains crop settings even when layout has not happened yet. */
    internal fun setCropArea(cropRect: RecognizeVisorCropRect) {
        if (cropArea == cropRect && cropBounds != null) return
        cropArea = cropRect
        updateGeometry(width, height)
    }

    /** Resolves geometry whenever Android gives the overlay its real size. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGeometry(w, h)
    }

    /** Rebuilds drawable paths only from a usable, current layout. */
    private fun updateGeometry(containerWidth: Int, containerHeight: Int) {
        val currentCropArea = cropArea ?: return
        if (containerWidth <= 0 || containerHeight <= 0) {
            cropBounds = null
            borderPath.reset()
            backgroundPath.reset()
            invalidate()
            return
        }

        val bounds = calculateVisorBounds(containerWidth, containerHeight, currentCropArea)
        cropBounds = bounds
        createPaths(bounds, containerWidth, containerHeight)
        onCropBoundsChanged?.invoke(bounds, containerWidth, containerHeight)
        invalidate()
    }

    /** Rebuilds the border and dimmed-background paths for [bounds]. */
    private fun createPaths(bounds: Rect, containerWidth: Int, containerHeight: Int) {
        val width = bounds.width.toFloat()
        val height = bounds.height.toFloat()
        val x = bounds.left.toFloat()
        val y = bounds.top.toFloat()
        val cornerLineLength = width * 0.05F
        val radius = cornerLineLength / 2
        val topLeftArcRect = RectF(x, y, x + radius, y + radius)
        val topRightArcRect = RectF(x + width - radius, y, x + width, y + radius)
        val bottomRightArcRect = RectF(
            x + width - radius,
            y + height - radius,
            x + width,
            y + height,
        )
        val bottomLeftArcRect = RectF(x, y + height - radius, x + radius, y + height)
        borderPath = Path().apply {
            // Top Left Corner
            roundCorner(
                from = PointF(x, y + cornerLineLength),
                to = PointF(x + cornerLineLength, y),
                startAngle = -180F,
                roundRect = topLeftArcRect,
            )

            // Top Right Corner
            roundCorner(
                from = PointF(x + width - cornerLineLength, y),
                to = PointF(x + width, y + cornerLineLength),
                startAngle = -90F,
                roundRect = topRightArcRect,
            )

            // Bottom Right Corner
            roundCorner(
                from = PointF(x + width, y + height - cornerLineLength),
                to = PointF(x + width - cornerLineLength, y + height),
                startAngle = 0F,
                roundRect = bottomRightArcRect,
            )

            // Bottom Left Corner
            roundCorner(
                from = PointF(x + cornerLineLength, y + height),
                to = PointF(x, y + height - cornerLineLength),
                startAngle = 90F,
                roundRect = bottomLeftArcRect,
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
            lineTo(containerWidth.toFloat(), containerHeight.toFloat())
            lineTo(containerWidth.toFloat(), 0F)
            lineTo(0F, 0F)
            lineTo(x, y)
            lineTo(x, y + height - radius)
            arcTo(bottomLeftArcRect, -180F, -90F)
            lineTo(x + width, height + y)
            lineTo(containerWidth.toFloat(), containerHeight.toFloat())
            lineTo(0F, containerHeight.toFloat())
            close()
        }
    }

    /** Draws the dimmed background and scanner recognition border. */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.apply {
            drawPath(backgroundPath, backgroundColor)
            cornerPaint.color = if (isActive) ACTIVE_BORDER_COLOR else INACTIVE_BORDER_COLOR
            drawPath(borderPath, cornerPaint)
        }
    }

    private companion object {
        const val BORDER_WIDTH_DP = 2F
        val ACTIVE_BORDER_COLOR = 0xFF43A047.toInt()
        val INACTIVE_BORDER_COLOR = 0xFF616161.toInt()
    }
}

/** Adds one rounded visor corner segment to this path. */
private fun Path.roundCorner(from: PointF, to: PointF, startAngle: Float, roundRect: RectF) {
    moveTo(from.x, from.y)
    arcTo(roundRect, startAngle, 90F)
    lineTo(to.x, to.y)
}

/** Calculates the visor rectangle for the overlay's current laid-out size. */
internal fun calculateVisorBounds(
    containerWidth: Int,
    containerHeight: Int,
    cropArea: RecognizeVisorCropRect,
): Rect {
    val visorWidth = containerWidth * cropArea.scaleWidth.toFloat()
    val visorHeight = containerHeight * cropArea.scaleHeight.toFloat()
    val left = containerWidth / 2F * (1F + cropArea.centerOffsetX.toFloat()) -
        visorWidth / 2F
    val top = containerHeight / 2F * (1F + cropArea.centerOffsetY.toFloat()) -
        visorHeight / 2F
    return Rect(
        left = left.roundToInt(),
        top = top.roundToInt(),
        right = (left + visorWidth).roundToInt(),
        bottom = (top + visorHeight).roundToInt(),
    )
}
