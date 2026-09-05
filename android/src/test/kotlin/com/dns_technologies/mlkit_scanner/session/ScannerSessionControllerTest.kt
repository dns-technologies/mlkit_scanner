package com.dns_technologies.mlkit_scanner.session

import android.content.Context
import android.os.Handler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

internal class ScannerSessionControllerTest {
    @Test
    fun `reuses current session and forwards mapped view arguments`() {
        val context = mock(Context::class.java)
        val session = mock(ScannerSession::class.java)
        val view = mock(ScannerView::class.java)
        val cropRect = RecognizeVisorCropRect(0.5, 0.6, 0.1, -0.2)
        doReturn(view).`when`(session).createView(context, VIEW_ID, 2.0, cropRect, true)
        val controller = controller().apply { setSession(session) }

        val actualView = controller.createView(
            context = context,
            platformViewId = VIEW_ID,
            creationParams = mapOf(
                "viewId" to VIEW_ID.toLong(),
                "initialZoomRatio" to 2.0F,
                "initialFlashEnabled" to true,
                "initialCropRect" to mapOf(
                    "scaleWidth" to 0.5F,
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
    fun `forwards absent optional view arguments as null`() {
        val context = mock(Context::class.java)
        val session = mock(ScannerSession::class.java)
        val view = mock(ScannerView::class.java)
        doReturn(view).`when`(session).createView(context, VIEW_ID, null, null, null)
        val controller = controller().apply { setSession(session) }

        val actualView = controller.createView(
            context = context,
            platformViewId = VIEW_ID,
            creationParams = mapOf("viewId" to VIEW_ID),
        )

        assertSame(view, actualView)
    }

    @Test
    fun `rejects invalid view arguments before session creation`() {
        val controller = controller()
        val invalidArguments = listOf<Any?>(
            null,
            emptyMap<String, Any?>(),
            mapOf("viewId" to -1),
            mapOf("viewId" to "invalid"),
            mapOf("viewId" to VIEW_ID, "initialZoomRatio" to 0.0),
            mapOf("viewId" to VIEW_ID, "initialZoomRatio" to Double.MIN_VALUE),
            mapOf("viewId" to VIEW_ID, "initialZoomRatio" to Double.MAX_VALUE),
            mapOf("viewId" to VIEW_ID, "initialFlashEnabled" to 1),
            mapOf("viewId" to VIEW_ID, "initialCropRect" to "invalid"),
        )

        invalidArguments.forEach { creationParams ->
            assertSame(
                PluginError.InvalidArguments,
                runCatching {
                    controller.createView(mock(Context::class.java), VIEW_ID, creationParams)
                }.exceptionOrNull(),
            )
            assertNull(controller.session)
        }
    }

    @Test
    fun `rejects mismatched platform and argument view ids before session creation`() {
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
    fun `controller exclusively observes host lifecycle and forwards its state`() {
        val host = TestHostLifecycleOwner(Lifecycle.State.RESUMED)
        val session = mock(ScannerSession::class.java)
        val controller = controller().apply { setSession(session) }

        controller.attachHostLifecycle(host.lifecycle)

        assertEquals(1, host.observerCount)
        verify(session).activate()

        host.moveTo(Lifecycle.State.STARTED)

        verify(session).deactivate()
        controller.detachHostLifecycle()

        assertEquals(0, host.observerCount)
        verify(session, never()).release()
        assertSame(session, controller.session)
    }

    @Test
    fun `replaced host lifecycle can no longer change session state`() {
        val firstHost = TestHostLifecycleOwner(Lifecycle.State.RESUMED)
        val secondHost = TestHostLifecycleOwner(Lifecycle.State.RESUMED)
        val session = mock(ScannerSession::class.java)
        val controller = controller().apply { setSession(session) }
        controller.attachHostLifecycle(firstHost.lifecycle)

        controller.attachHostLifecycle(secondHost.lifecycle)

        assertEquals(0, firstHost.observerCount)
        assertEquals(1, secondHost.observerCount)
        clearInvocations(session)

        firstHost.moveTo(Lifecycle.State.STARTED)

        verify(session, never()).deactivate()
        secondHost.moveTo(Lifecycle.State.STARTED)
        verify(session).deactivate()
    }

    @Test
    fun `destroyed host lifecycle is detached from controller`() {
        val host = TestHostLifecycleOwner(Lifecycle.State.RESUMED)
        val session = mock(ScannerSession::class.java)
        val controller = controller().apply { setSession(session) }
        controller.attachHostLifecycle(host.lifecycle)
        clearInvocations(session)

        host.moveTo(Lifecycle.State.DESTROYED)

        assertEquals(0, host.observerCount)
        verify(session).deactivate()
    }

    @Test
    fun `release clears and disposes current session`() {
        val session = mock(ScannerSession::class.java)
        val controller = controller().apply { setSession(session) }

        controller.release()

        assertNull(controller.session)
        verify(session).release()
    }

    private fun controller() = ScannerSessionController(
        mainHandler = mock(Handler::class.java),
        scanResultSink = ScanResultSink { _, _ -> },
    )

    private fun ScannerSessionController.setSession(session: ScannerSession) {
        javaClass.getDeclaredField("session").apply { isAccessible = true }.set(this, session)
    }

    private class TestHostLifecycleOwner(
        initialState: Lifecycle.State,
    ) : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = initialState
        }

        override val lifecycle: Lifecycle
            get() = registry

        val observerCount: Int
            get() = registry.observerCount

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    private companion object {
        const val VIEW_ID = 42
    }
}
