package com.dns_technologies.mlkit_scanner.scanner.components.camera.x

import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraPreviewDescription
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraPreviewState
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import io.flutter.view.TextureRegistry
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/** Manages a Flutter texture's surfaces, frame readiness and metadata. */
internal class CameraTextureOutput(
    /** Flutter-owned texture producer released after all provided surfaces return. */
    private val producer: TextureRegistry.SurfaceProducer,
    /** Serial callback executor used for CameraX surface and transformation updates. */
    private val executor: Executor,
    /** Publishes texture metadata or null when the preview becomes unavailable. */
    private val publish: (CameraPreviewDescription?) -> Unit,
) : Preview.SurfaceProvider {
    /** Current CameraX surface request, also used to reject stale frame completions. */
    private var request: SurfaceRequest? = null
    /** Surface borrowed from the producer for the current request. */
    private var surface: Surface? = null
    /** Latest crop and rotation metadata belonging to the current request. */
    private var transformation: SurfaceRequest.TransformationInfo? = null
    /** Whether a completed capture has confirmed the current request is streaming. */
    private var streaming = false
    /** Whether this preview can retain a captured frame while paused. */
    private var hasFrame = false
    /** Terminal flag rejecting new surfaces and late readiness callbacks. */
    private var closed = false
    /** Requests whose surface-return callbacks must complete before texture release. */
    private val provided = mutableSetOf<SurfaceRequest>()
    /** Completion of producer release, including any release failure. */
    private val released = CompletableDeferred<Unit>()
    /** Awaitable completion of texture release after CameraX returns its surfaces. */
    val disposal: Deferred<Unit>
        get() = released

    /** Notifies that the texture surface is unavailable. */
    var onSurfaceLost: () -> Unit = {}
    /** Notifies that the texture surface is available again. */
    var onSurfaceRestored: () -> Unit = {}

    init {
        producer.setCallback(
            object : TextureRegistry.SurfaceProducer.Callback {
                override fun onSurfaceCleanup() {
                    if (closed) return
                    clearRequest()
                    hasFrame = false
                    publish(null)
                    onSurfaceLost()
                }

                override fun onSurfaceAvailable() {
                    if (!closed) onSurfaceRestored()
                }
            }
        )
    }

    override fun onSurfaceRequested(next: SurfaceRequest) {
        if (closed) {
            next.willNotProvideSurface()
            return
        }
        clearRequest()
        hasFrame = false
        request = next
        producer.setSize(next.resolution.width, next.resolution.height)
        val target = producer.surface
        surface = target
        next.setTransformationInfoListener(executor) { info ->
            if (request !== next || closed) return@setTransformationInfoListener
            transformation = info
            emit()
        }
        provided.add(next)
        try {
            next.provideSurface(target, executor) {
                provided.remove(next)
                if (request === next) {
                    request = null
                    surface = null
                    transformation = null
                }
                finishDisposal()
            }
        } catch (error: Exception) {
            provided.remove(next)
            clearRequest()
            throw error
        }
    }

    /** Associates a real capture with the surface request active when it began. */
    fun frameStarted(frameNumber: Long): PreviewFrame? =
        request?.let { PreviewFrame(frameNumber, it) }

    /** Capture completion is a readiness signal, not a Flutter raster presentation timestamp. */
    fun frameCaptured(frame: PreviewFrame) {
        if (closed || streaming || surface == null || request !== frame.output) return
        streaming = true
        hasFrame = true
        emit()
    }

    /**
     * A real camera capture associated with the surface request active when it started.
     *
     * @property number Camera2 frame number used to correlate capture callbacks.
     * @property output Surface request whose readiness this capture can confirm.
     */
    class PreviewFrame(val number: Long, val output: SurfaceRequest)

    /** Pauses preview delivery while retaining the last available frame. */
    fun pause() {
        if (closed) return
        emit(if (hasFrame) CameraPreviewState.Paused else CameraPreviewState.Starting)
        clearRequest()
    }

    /** Marks a closed camera as paused or starting without discarding retained pixels. */
    fun cameraAvailable(available: Boolean) {
        if (!available) {
            emit(if (hasFrame) CameraPreviewState.Paused else CameraPreviewState.Starting)
            streaming = false
        }
    }

    /** Publishes the source crop and rotation not already applied by the producer. */
    private fun emit(
        state: CameraPreviewState =
            if (streaming) CameraPreviewState.Streaming else CameraPreviewState.Starting,
    ) {
        val info = transformation ?: return
        val size = request?.resolution ?: return
        val automatic = producer.handlesCropAndRotation()
        val rotation = info.rotationDegrees
        val swapped = automatic && rotation % 180 != 0
        val crop =
            if (automatic)
                Rect(
                    0,
                    0,
                    if (swapped) size.height else size.width,
                    if (swapped) size.width else size.height,
                )
            else info.cropRect.let { Rect(it.left, it.top, it.right, it.bottom) }
        publish(
            CameraPreviewDescription(
                textureId = producer.id(),
                width = if (swapped) size.height else size.width,
                height = if (swapped) size.width else size.height,
                rotationDegrees = if (automatic) 0 else rotation,
                mirrored = false,
                state = state,
                cropRect = crop,
            )
        )
    }

    /** Withdraws the current camera surface. */
    private fun clearRequest() {
        val previous = request
        request = null
        surface = null
        transformation = null
        streaming = false
        previous?.clearTransformationInfoListener()
        previous?.invalidate()
    }

    /** Rejects further work and starts release once all provided surfaces return. */
    fun dispose() {
        if (closed) return
        closed = true
        producer.setCallback(null)
        clearRequest()
        publish(null)
        finishDisposal()
    }

    /** Releases the producer exactly once after CameraX has relinquished every surface. */
    private fun finishDisposal() {
        if (!closed || provided.isNotEmpty() || released.isCompleted) return
        try {
            producer.release()
            released.complete(Unit)
        } catch (error: Exception) {
            released.completeExceptionally(error)
        }
    }
}
