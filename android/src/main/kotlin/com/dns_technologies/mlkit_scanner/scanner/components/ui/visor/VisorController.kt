package com.dns_technologies.mlkit_scanner.scanner.components.ui.visor

import android.view.View
import android.widget.FrameLayout
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect

/** Creates and updates scanner visor overlay state. */
internal class VisorController(
    private val boundsView: FrameLayout,
    private val anchorView: View,
    private val onCenterChanged: (offsetX: Float, offsetY: Float) -> Unit,
) {
    private var visor: VisorView? = null
    private var isScanActive = false

    /** Creates the visor immediately and lets it resolve geometry from its own layout. */
    fun setCropArea(cropRect: RecognizeVisorCropRect) {
        val activeVisor = visor ?: addVisor()
        activeVisor.isActive = isScanActive
        activeVisor.setCropArea(cropRect)
    }

    /** Updates the visual active state of the current visor overlay. */
    fun setScanActive(isActive: Boolean) {
        isScanActive = isActive
        visor?.isActive = isActive
    }

    /** Removes the visor view owned by this controller. */
    fun dispose() {
        visor?.let { currentVisor ->
            currentVisor.onCropBoundsChanged = null
            boundsView.removeView(currentVisor)
        }
        visor = null
    }

    /** Adds the visor below the anchor overlay. */
    private fun addVisor(): VisorView {
        return VisorView(boundsView.context).also { newVisor ->
            newVisor.onCropBoundsChanged = { bounds, containerWidth, containerHeight ->
                onCenterChanged(
                    bounds.left + bounds.width / 2F - containerWidth / 2F,
                    bounds.top + bounds.height / 2F - containerHeight / 2F,
                )
            }
            val layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            val anchorIndex = boundsView.indexOfChild(anchorView)
            if (anchorView.parent === boundsView && anchorIndex >= 0) {
                boundsView.addView(newVisor, anchorIndex, layoutParams)
            } else {
                boundsView.addView(newVisor, layoutParams)
            }
            visor = newVisor
        }
    }
}
