package com.dns_technologies.mlkit_scanner.controllers

import android.content.Context
import android.os.Handler
import androidx.lifecycle.Lifecycle
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.models.ScannerSession
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

internal class ScannerControllerTest {
    @Test
    fun `reuses current session and forwards mapped registration`() {
        val context = mock(Context::class.java)
        val session = mock(ScannerSession::class.java)
        val view = mock(ScannerView::class.java)
        val cropRect = RecognizeVisorCropRect(0.5, 0.6, 0.1, -0.2)
        doReturn(view).`when`(session).createView(context, VIEW_ID, 0.75, cropRect, true)
        val controller = controller().apply { setSession(session) }

        val actualView = controller.createView(
            context = context,
            platformViewId = VIEW_ID,
            creationParams = mapOf(
                "viewId" to VIEW_ID,
                "initialZoom" to 0.75,
                "initialFlashEnabled" to true,
                "initialCropRect" to mapOf(
                    "scaleWidth" to 0.5,
                    "scaleHeight" to 0.6,
                    "offsetX" to 0.1,
                    "offsetY" to -0.2,
                ),
            ),
        )

        assertSame(view, actualView)
        assertSame(session, controller.session)
    }

    @Test
    fun `rejects mismatched Flutter and registration view ids before session creation`() {
        val controller = controller()

        assertSame(
            PluginError.InvalidArguments,
            runCatching {
                controller.createView(
                    mock(Context::class.java),
                    VIEW_ID,
                    mapOf("viewId" to VIEW_ID + 1),
                )
            }.exceptionOrNull(),
        )
        assertNull(controller.session)
    }

    @Test
    fun `attaches and detaches host lifecycle on current session`() {
        val lifecycle = mock(Lifecycle::class.java)
        val session = mock(ScannerSession::class.java)
        val controller = controller().apply { setSession(session) }

        controller.attachHostLifecycle(lifecycle)
        controller.detachHostLifecycle()

        verify(session).attachHostLifecycle(lifecycle)
        verify(session).detachHostLifecycle()
        verify(session, never()).release()
        assertSame(session, controller.session)
    }

    @Test
    fun `release clears and disposes current session`() {
        val session = mock(ScannerSession::class.java)
        val controller = controller().apply { setSession(session) }

        controller.release()

        assertNull(controller.session)
        verify(session).release()
    }

    private fun controller() = ScannerController(
        mainHandler = mock(Handler::class.java),
        scanResultSink = ScanResultSink { _, _ -> },
    )

    private fun ScannerController.setSession(session: ScannerSession) {
        javaClass.getDeclaredField("session").apply { isAccessible = true }.set(this, session)
    }

    private companion object {
        const val VIEW_ID = 42
    }
}
