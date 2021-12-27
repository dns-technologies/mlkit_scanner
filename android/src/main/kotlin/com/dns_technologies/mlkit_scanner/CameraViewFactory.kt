package com.dns_technologies.mlkit_scanner

import android.content.Context
import com.otaliastudios.cameraview.CameraView
import com.otaliastudios.cameraview.controls.Audio
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

typealias OnCreateViewListener = (view: CameraView) -> Unit

/** Factory create camera [PlatformView] */
class CameraViewFactory(private val onCreate: OnCreateViewListener): PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val creationParams = args as Map<String?, Any?>?
        val cameraView = CameraView(context)
        cameraView.playSounds = false
        cameraView.audio = Audio.OFF
        onCreate.invoke(cameraView)
        return com.dns_technologies.mlkit_scanner.CameraPlatformView(cameraView, viewId, creationParams)
    }
}