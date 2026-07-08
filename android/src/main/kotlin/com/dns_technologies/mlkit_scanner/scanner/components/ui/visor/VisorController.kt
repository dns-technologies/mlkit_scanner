package com.dns_technologies.mlkit_scanner.scanner.components.ui.visor

import android.view.View
import android.widget.FrameLayout
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect

/** Creates and updates scanner visor overlay state. */
internal class VisorController(
    private val boundsView: FrameLayout,
    private val anchorView: View,
) {
    private var visor: VisorView? = null

    /** Creates or updates the visor overlay for the requested crop area. */
    fun setCropArea(cropRect: RecognizeVisorCropRect, isScanActive: Boolean) {
        val activeVisor = visor ?: addVisor(cropRect)
        activeVisor.cropRect = cropRect
        activeVisor.isActive = isScanActive
    }

    /** Updates the visual active state of the current visor overlay. */
    fun setScanActive(isActive: Boolean) {
        visor?.isActive = isActive
    }

    /** Adds the visor below the anchor overlay. */
    private fun addVisor(cropRect: RecognizeVisorCropRect): VisorView {
        return VisorView(cropRect, boundsView.context).also { newVisor ->
            val anchorIndex = boundsView.indexOfChild(anchorView)
            if (anchorView.parent === boundsView && anchorIndex >= 0) {
                boundsView.addView(newVisor, anchorIndex)
            } else {
                boundsView.addView(newVisor)
            }
            visor = newVisor
        }
    }
}
