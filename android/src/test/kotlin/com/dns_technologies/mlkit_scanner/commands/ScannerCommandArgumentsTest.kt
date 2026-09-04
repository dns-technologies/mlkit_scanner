package com.dns_technologies.mlkit_scanner.commands

import android.content.Context
import androidx.lifecycle.Lifecycle
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

internal class ScannerCommandArgumentsTest {
    private val commandScope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `start scan parses barcode type and non-negative integer delay`() {
        val session = RecordingScannerSession()
        val result = mock(MethodChannel.Result::class.java)

        StartScanCommand { session }.execute(
            MethodCall(
                "startScan",
                mapOf("viewId" to VIEW_ID, "type" to 0, "delay" to 150L),
            ),
            result,
        )

        assertEquals(VIEW_ID to 150, session.startScanArguments)
        verify(result).success(true)
    }

    @Test
    fun `start scan rejects unsupported type and malformed arguments`() {
        listOf(
            mapOf("viewId" to VIEW_ID, "type" to 1, "delay" to 0),
            mapOf("viewId" to VIEW_ID, "type" to 0, "delay" to -1),
            mapOf("viewId" to VIEW_ID, "type" to 0, "delay" to 1.5),
            mapOf("type" to 0, "delay" to 0),
        ).forEach { arguments ->
            assertInvalid {
                StartScanCommand { null }.execute(MethodCall("startScan", arguments), it)
            }
        }
    }

    @Test
    fun `zoom parses positive absolute ratio`() {
        val session = RecordingScannerSession()
        val result = mock(MethodChannel.Result::class.java)

        SetZoomRatioCommand({ session }, commandScope).execute(
            MethodCall("setZoomRatio", mapOf("viewId" to VIEW_ID, "value" to 2.5)),
            result,
        )

        assertEquals(VIEW_ID to 2.5F, session.zoomArguments)
        verify(result).success(true)
    }

    @Test
    fun `zoom rejects values without a positive finite Float representation`() {
        listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.MIN_VALUE,
            Double.MAX_VALUE,
            -0.01,
            0.0,
            "2.0",
        ).forEach { value ->
            assertInvalid {
                SetZoomRatioCommand({ null }, commandScope).execute(
                    MethodCall("setZoomRatio", mapOf("viewId" to VIEW_ID, "value" to value)),
                    it,
                )
            }
        }
        assertInvalid {
            SetZoomRatioCommand({ null }, commandScope).execute(
                MethodCall("setZoomRatio", mapOf("value" to 2.0)),
                it,
            )
        }
    }

    @Test
    fun `crop area parses defaults`() {
        val session = RecordingScannerSession()
        val result = mock(MethodChannel.Result::class.java)

        SetCropAreaCommand { session }.execute(
            MethodCall(
                "setCropArea",
                mapOf("viewId" to VIEW_ID, "cropRect" to emptyMap<String, Any?>()),
            ),
            result,
        )

        assertEquals(VIEW_ID to RecognizeVisorCropRect(), session.cropArguments)
        verify(result).success(true)
    }

    @Test
    fun `crop area rejects invalid components`() {
        listOf(
            mapOf("scaleWidth" to 0.0),
            mapOf("scaleHeight" to -1.0),
            mapOf("offsetX" to Double.NaN),
            mapOf("offsetY" to Double.NEGATIVE_INFINITY),
            mapOf("scaleWidth" to "0.5"),
        ).forEach { cropRect ->
            assertInvalid {
                SetCropAreaCommand { null }.execute(
                    MethodCall(
                        "setCropArea",
                        mapOf("viewId" to VIEW_ID, "cropRect" to cropRect),
                    ),
                    it,
                )
            }
        }
        assertInvalid {
            SetCropAreaCommand { null }.execute(
                MethodCall("setCropArea", mapOf("viewId" to VIEW_ID)),
                it,
            )
        }
    }

    @Test
    fun `scan delay parses view-scoped non-negative value`() {
        val session = RecordingScannerSession()
        val result = mock(MethodChannel.Result::class.java)

        SetScanDelayCommand { session }.execute(
            MethodCall("setScanDelay", mapOf("viewId" to VIEW_ID, "delay" to 150L)),
            result,
        )

        assertEquals(VIEW_ID to 150, session.scanDelayArguments)
        verify(result).success(true)
    }

    @Test
    fun `scan delay rejects invalid arguments`() {
        listOf(
            mapOf("viewId" to VIEW_ID, "delay" to -1),
            mapOf("viewId" to VIEW_ID, "delay" to 150.5),
            mapOf("delay" to 150),
        ).forEach { arguments ->
            assertInvalid {
                SetScanDelayCommand { null }.execute(MethodCall("setScanDelay", arguments), it)
            }
        }
    }

    private fun assertInvalid(execute: (MethodChannel.Result) -> Unit) {
        val result = mock(MethodChannel.Result::class.java)
        execute(result)
        verify(result).error(
            PluginError.InvalidArguments.errorCode,
            PluginError.InvalidArguments.message,
            null,
        )
    }

    private class RecordingScannerSession : ScannerSession {
        var startScanArguments: Pair<Int, Int>? = null
            private set
        var zoomArguments: Pair<Int, Float>? = null
            private set
        var cropArguments: Pair<Int, RecognizeVisorCropRect>? = null
            private set
        var scanDelayArguments: Pair<Int, Int>? = null
            private set

        override fun createView(
            context: Context,
            viewId: Int,
            initialZoomRatio: Double?,
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
        override suspend fun toggleFlashLight(viewId: Int) = Unit

        override fun startScan(viewId: Int, periodMs: Int) {
            startScanArguments = viewId to periodMs
        }

        override fun pauseScan(viewId: Int) = Unit

        override fun updateScanPeriod(viewId: Int, periodMs: Int) {
            scanDelayArguments = viewId to periodMs
        }

        override suspend fun setZoomRatio(viewId: Int, value: Float) {
            zoomArguments = viewId to value
        }

        override fun setCropArea(viewId: Int, cropRect: RecognizeVisorCropRect) {
            cropArguments = viewId to cropRect
        }

        override fun release() = Unit
    }

    private companion object {
        const val VIEW_ID = 42
    }
}
