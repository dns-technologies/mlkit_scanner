package com.dns_technologies.mlkit_scanner

import android.view.ViewGroup
import com.otaliastudios.cameraview.CameraView
import com.otaliastudios.cameraview.controls.Preview
import io.flutter.plugin.platform.PlatformView

/**
 * Native [CameraView] widget for use in flutter application
 *
 * Prepares the camera widget [cameraPreview] based on the parameters passed from the flutter
 */
class CameraPlatformView(private val cameraPreview: CameraView, id: Int, creationParams: Map<String?, Any?>?): PlatformView {
    init {
        cameraPreview.preview = Preview.TEXTURE
        cameraPreview.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun getView() = cameraPreview

    override fun dispose() {
        cameraPreview.destroy();
    }
}