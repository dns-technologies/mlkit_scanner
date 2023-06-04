package com.dns_technologies.mlkit_scanner

import android.content.Context
import com.dns_technologies.mlkit_scanner.configs.ImageProcessingConfig
import com.otaliastudios.cameraview.CameraView
import com.otaliastudios.cameraview.controls.Audio
import com.otaliastudios.cameraview.controls.Engine
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

typealias OnCreateViewListener = (view: CameraView) -> Unit

/** Factory create camera [PlatformView] */
class CameraViewFactory(private val onCreate: OnCreateViewListener) :
    PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context?, viewId: Int, args: Any?): PlatformView {
        val creationParams = args as Map<String?, Any?>?
        val cameraView = CameraView(context!!)
        with(cameraView) {
            // todo(ilyushin): Убрать после тестов (или вынести как параметр)
            setExperimental(true)
            engine = Engine.CAMERA2
            playSounds = false
            audio = Audio.OFF
            with(ImageProcessingConfig) {
                frameProcessingMaxWidth = resolution.first
                frameProcessingMaxHeight = resolution.second
            }
        }
        onCreate.invoke(cameraView)
        return CameraPlatformView(cameraView, viewId, creationParams)
    }
}