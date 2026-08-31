package com.dns_technologies.mlkit_scanner.commands

import android.content.Context
import androidx.lifecycle.Lifecycle
import com.dns_technologies.mlkit_scanner.CameraControlOperation
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

internal class ScannerCommandStateTest {
    private val commandScope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `commands requiring a session report camera not initialized when it is absent`() {
        val executions = listOf<(MethodChannel.Result) -> Unit>(
            { result ->
                StartScanCommand { null }.execute(
                    MethodCall(
                        "startScan",
                        mapOf("viewId" to VIEW_ID, "type" to 0, "delay" to 0),
                    ),
                    result,
                )
            },
            { result ->
                ResumeCameraCommand { null }.execute(
                    MethodCall("resume", mapOf("viewId" to VIEW_ID)),
                    result,
                )
            },
            { result ->
                SetScanDelayCommand { null }.execute(
                    MethodCall("delay", mapOf("viewId" to VIEW_ID, "delay" to 100)),
                    result,
                )
            },
            { result ->
                SetCropAreaCommand { null }.execute(
                    MethodCall(
                        "crop",
                        mapOf(
                            "viewId" to VIEW_ID,
                            "cropRect" to emptyMap<String, Any?>(),
                        ),
                    ),
                    result,
                )
            },
            { result ->
                SetZoomCommand({ null }, commandScope).execute(
                    MethodCall("zoom", mapOf("viewId" to VIEW_ID, "value" to 0.5)),
                    result,
                )
            },
            { result ->
                ToggleFlashCommand({ null }, commandScope).execute(
                    MethodCall("torch", mapOf("viewId" to VIEW_ID)),
                    result,
                )
            },
        )

        executions.forEach { execute ->
            val result = mock(MethodChannel.Result::class.java)
            execute(result)
            verify(result).error(
                PluginError.CameraIsNotInitialized.errorCode,
                PluginError.CameraIsNotInitialized.message,
                null,
            )
        }
    }

    @Test
    fun `stop commands remain idempotent without a session`() {
        val executions = listOf<(MethodChannel.Result) -> Unit>(
            { result ->
                PauseCameraCommand { null }.execute(
                    MethodCall("pause", mapOf("viewId" to VIEW_ID)),
                    result,
                )
            },
            { result ->
                CancelScanCommand { null }.execute(
                    MethodCall("cancel", mapOf("viewId" to VIEW_ID)),
                    result,
                )
            },
        )

        executions.forEach { execute ->
            val result = mock(MethodChannel.Result::class.java)
            execute(result)
            verify(result).success(true)
        }
    }

    @Test
    fun `zoom command completes only after camera control succeeds`() {
        val session = ControlSession()
        val result = mock(MethodChannel.Result::class.java)

        SetZoomCommand({ session }, commandScope).execute(
            MethodCall("zoom", mapOf("viewId" to VIEW_ID, "value" to 0.5)),
            result,
        )

        verify(result, never()).success(anyValue())
        assertEquals(VIEW_ID, session.zoomViewId)
        session.zoomResult.complete(Unit)
        verify(result).success(true)
    }

    @Test
    fun `torch command completes only after camera control succeeds`() {
        val session = ControlSession()
        val result = mock(MethodChannel.Result::class.java)

        ToggleFlashCommand({ session }, commandScope).execute(
            MethodCall("torch", mapOf("viewId" to VIEW_ID)),
            result,
        )

        verify(result, never()).success(anyValue())
        assertEquals(VIEW_ID, session.torchViewId)
        session.torchResult.complete(Unit)
        verify(result).success(true)
    }

    @Test
    fun `camera control failure is returned with stable error`() {
        val session = ControlSession()
        val result = mock(MethodChannel.Result::class.java)
        val cause = IllegalStateException("CameraX rejected zoom")
        val error = PluginError.CameraControlError(
            operation = CameraControlOperation.ZOOM,
            viewId = VIEW_ID,
            cause = cause,
        )

        SetZoomCommand({ session }, commandScope).execute(
            MethodCall("zoom", mapOf("viewId" to VIEW_ID, "value" to 0.5)),
            result,
        )
        session.zoomResult.completeExceptionally(error)

        verify(result).error(
            PluginError.CameraControlError.ERROR_CODE,
            PluginError.CameraControlError.ERROR_MESSAGE,
            error.details,
        )
        verify(result, never()).success(anyValue())
    }

    private class ControlSession : ScannerSession {
        val zoomResult = CompletableDeferred<Unit>()
        val torchResult = CompletableDeferred<Unit>()
        var zoomViewId: Int? = null
            private set
        var torchViewId: Int? = null
            private set

        override fun createView(
            context: Context,
            viewId: Int,
            initialZoom: Double?,
            initialCropRect: RecognizeVisorCropRect?,
            initialFlashEnabled: Boolean?,
        ): ScannerView = mock(ScannerView::class.java)

        override suspend fun captureCamera(
            viewId: Int,
            requestCameraPermission: suspend () -> Boolean,
        ) = Unit
        override fun releaseCamera(viewId: Int) = Unit

        override fun resumeCamera(viewId: Int) = Unit
        override fun pauseCamera(viewId: Int) = Unit
        override fun attachHostLifecycle(lifecycle: Lifecycle) = Unit
        override fun detachHostLifecycle() = Unit
        override suspend fun toggleFlashLight(viewId: Int) {
            torchViewId = viewId
            torchResult.await()
        }
        override fun startScan(viewId: Int, periodMs: Int) = Unit
        override fun pauseScan(viewId: Int) = Unit
        override fun updateScanPeriod(viewId: Int, periodMs: Int) = Unit
        override suspend fun setZoom(viewId: Int, value: Float) {
            zoomViewId = viewId
            zoomResult.await()
        }
        override fun setCropArea(viewId: Int, cropRect: RecognizeVisorCropRect) = Unit
        override fun release() = Unit
    }

    private fun <T> anyValue(): T = any<T>()

    private companion object {
        const val VIEW_ID = 42
    }
}
