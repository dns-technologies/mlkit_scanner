package com.dns_technologies.mlkit_scanner.scanner.components.camera.backend

import android.view.View
import androidx.lifecycle.LifecycleOwner
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnCameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnError
import com.dns_technologies.mlkit_scanner.scanner.components.camera.OnInit
import java.util.concurrent.ExecutorService

/**
 * Minimal adapter implemented by a concrete camera library integration.
 */
interface CameraBackend {
    val previewView: View

    fun start(
        lifecycleOwner: LifecycleOwner,
        analysisExecutor: ExecutorService,
        onFrame: OnCameraFrame,
        onInit: OnInit,
        onError: OnError,
    )

    fun isActive(): Boolean
    fun toggleFlashLight()
    fun focusOnCenter(resetDelayMs: Long, offsetX: Float, offsetY: Float)
    fun setZoom(value: Float)
    fun release()
}
