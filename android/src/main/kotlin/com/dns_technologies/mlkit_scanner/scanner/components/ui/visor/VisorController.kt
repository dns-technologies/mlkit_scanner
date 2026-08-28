package com.dns_technologies.mlkit_scanner.scanner.components.ui.visor

import android.view.View
import android.widget.FrameLayout
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import kotlin.math.roundToInt

/** Creates and updates scanner visor overlay state. */
internal class VisorController(
    private val boundsView: FrameLayout,
    private val anchorView: View,
    private val onCenterChanged: (offsetX: Float, offsetY: Float) -> Unit,
) {
    private var visor: VisorView? = null
    private var cropArea: RecognizeVisorCropRect? = null
    private var appliedCropArea: RecognizeVisorCropRect? = null
    private var appliedWidth = 0
    private var appliedHeight = 0
    private var isScanActive = false
    private var isObservingLayout = false
    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        applyBounds()
    }

    /** Creates or updates the visor overlay for the requested crop area. */
    fun setCropArea(cropRect: RecognizeVisorCropRect, isScanActive: Boolean) {
        cropArea = cropRect
        this.isScanActive = isScanActive
        startObservingLayout()
        applyBounds()
    }

    /** Updates the visual active state of the current visor overlay. */
    fun setScanActive(isActive: Boolean) {
        isScanActive = isActive
        visor?.isActive = isActive
    }

    /** Removes the visor view owned by this controller. */
    fun dispose() {
        stopObservingLayout()
        visor?.let(boundsView::removeView)
        visor = null
        cropArea = null
        appliedCropArea = null
        appliedWidth = 0
        appliedHeight = 0
    }

    /** Adds the visor below the anchor overlay. */
    private fun addVisor(): VisorView {
        return VisorView(boundsView.context).also { newVisor ->
            val anchorIndex = boundsView.indexOfChild(anchorView)
            if (anchorView.parent === boundsView && anchorIndex >= 0) {
                boundsView.addView(newVisor, anchorIndex)
            } else {
                boundsView.addView(newVisor)
            }
            visor = newVisor
        }
    }

    private fun applyBounds() {
        val currentCropArea = cropArea ?: return
        val containerWidth = boundsView.width
        val containerHeight = boundsView.height
        if (containerWidth <= 0 || containerHeight <= 0) return

        val activeVisor = visor ?: addVisor()
        activeVisor.isActive = isScanActive
        if (
            appliedWidth == containerWidth &&
            appliedHeight == containerHeight &&
            appliedCropArea == currentCropArea
        ) {
            // Flutter may recreate the PlatformView render target without changing its geometry.
            activeVisor.invalidate()
            return
        }

        val bounds = calculateVisorBounds(containerWidth, containerHeight, currentCropArea)

        appliedWidth = containerWidth
        appliedHeight = containerHeight
        appliedCropArea = currentCropArea
        activeVisor.renderBounds(bounds)
        onCenterChanged(
            bounds.left + bounds.width / 2F - containerWidth / 2F,
            bounds.top + bounds.height / 2F - containerHeight / 2F,
        )
    }

    private fun startObservingLayout() {
        if (isObservingLayout) return
        isObservingLayout = true
        boundsView.addOnLayoutChangeListener(layoutChangeListener)
    }

    private fun stopObservingLayout() {
        if (!isObservingLayout) return
        isObservingLayout = false
        boundsView.removeOnLayoutChangeListener(layoutChangeListener)
    }
}

/** Calculates the visor rectangle once for its current container and crop settings. */
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
