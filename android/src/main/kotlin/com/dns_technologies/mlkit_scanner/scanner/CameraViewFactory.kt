package com.dns_technologies.mlkit_scanner.scanner

import android.content.Context
import com.dns_technologies.mlkit_scanner.scanner.components.camera.backend.CameraBackend
import com.dns_technologies.mlkit_scanner.scanner.components.camera.backend.CameraXCameraBackend
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

typealias OnCreateCameraListener = (camera: ScannerCamera) -> Unit
typealias OnDisposeCameraListener = (camera: ScannerCamera) -> Unit
typealias CreateCameraBackend = (context: Context) -> CameraBackend

/**
 * Creates scanner camera platform views.
 */
class CameraViewFactory(
    private val createCameraBackend: CreateCameraBackend = ::CameraXCameraBackend,
    private val onCreate: OnCreateCameraListener,
    private val onDispose: OnDisposeCameraListener,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context?, viewId: Int, args: Any?): PlatformView {
        val safeContext = context!!
        val camera = ScannerCamera(
            context = safeContext,
            backend = createCameraBackend(safeContext),
            onDispose = onDispose,
        )
        onCreate.invoke(camera)
        return camera
    }
}
