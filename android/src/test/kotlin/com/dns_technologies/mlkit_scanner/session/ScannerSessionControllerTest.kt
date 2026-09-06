package com.dns_technologies.mlkit_scanner.session

import android.content.Context
import android.os.Handler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.times
import org.mockito.ArgumentMatchers.any

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

    @Test
    fun `late release notification from old session cannot forget its replacement`() {
        val first = mock(ScannerSession::class.java)
        val second = mock(ScannerSession::class.java)
        val sessions = ArrayDeque(listOf(first, second))
        val releaseCallbacks = mutableListOf<(ScannerSession) -> Unit>()
        val controller = ScannerSessionController(
            mainHandler = mock(Handler::class.java),
            scanResultSink = ScanResultSink { _, _ -> },
            sessionFactory = { _, onRelease ->
                releaseCallbacks += onRelease
                sessions.removeFirst()
            },
        )
        val context = mock(Context::class.java)
        controller.createView(context, VIEW_ID, mapOf("viewId" to VIEW_ID))
        controller.release()
        controller.createView(context, VIEW_ID, mapOf("viewId" to VIEW_ID))

        releaseCallbacks.first()(first)

        assertSame(second, controller.session)
        releaseCallbacks.last()(second)
        assertNull(controller.session)
    }

    @Test
    fun `already dequeued lifecycle event cannot detach a replacement host`() {
        val firstHost = mock(Lifecycle::class.java)
        doReturn(Lifecycle.State.RESUMED).`when`(firstHost).currentState
        val firstOwner = mock(LifecycleOwner::class.java)
        doReturn(firstHost).`when`(firstOwner).lifecycle
        val secondHost = TestHostLifecycleOwner(Lifecycle.State.RESUMED)
        val session = mock(ScannerSession::class.java)
        val controller = controller().apply { setSession(session) }
        val observers = mutableListOf<LifecycleEventObserver>()
        doAnswer { invocation ->
            observers += invocation.getArgument<LifecycleEventObserver>(0)
            null
        }.`when`(firstHost).addObserver(anyValue())
        controller.attachHostLifecycle(firstHost)
        controller.attachHostLifecycle(secondHost.lifecycle)
        clearInvocations(session)

        observers.single().onStateChanged(firstOwner, Lifecycle.Event.ON_DESTROY)

        assertEquals(1, secondHost.observerCount)
        verify(session, never()).deactivate()
    }

    @Test
    fun `first view creation failure releases the newly created session`() {
        val context = mock(Context::class.java)
        val session = mock(ScannerSession::class.java)
        val failure = IllegalStateException("view creation failed")
        doThrow(failure).`when`(session).createView(context, VIEW_ID, null, null, null)
        val controller = ScannerSessionController(
            mainHandler = mock(Handler::class.java),
            scanResultSink = ScanResultSink { _, _ -> },
            sessionFactory = { _, _ -> session },
        )

        val error = runCatching {
            controller.createView(context, VIEW_ID, mapOf("viewId" to VIEW_ID))
        }.exceptionOrNull()

        assertSame(failure, error)
        assertNull(controller.session)
        verify(session).release()
    }

    @Test
    fun `view creation failure does not release an existing session`() {
        val context = mock(Context::class.java)
        val session = mock(ScannerSession::class.java)
        val failure = IllegalStateException("view creation failed")
        doThrow(failure).`when`(session).createView(context, VIEW_ID, null, null, null)
        val controller = controller().apply { setSession(session) }

        val error = runCatching {
            controller.createView(context, VIEW_ID, mapOf("viewId" to VIEW_ID))
        }.exceptionOrNull()

        assertSame(failure, error)
        assertSame(session, controller.session)
        verify(session, never()).release()
    }

    @Test
    fun `failed first view preserves its error when session cleanup also fails`() {
        val context = mock(Context::class.java)
        val session = mock(ScannerSession::class.java)
        val failure = IllegalStateException("view creation failed")
        val cleanupFailure = IllegalStateException("session cleanup failed")
        doThrow(failure).`when`(session).createView(context, VIEW_ID, null, null, null)
        doThrow(cleanupFailure).`when`(session).release()
        val controller = ScannerSessionController(
            mainHandler = mock(Handler::class.java),
            scanResultSink = ScanResultSink { _, _ -> },
            sessionFactory = { _, _ -> session },
        )

        val error = runCatching {
            controller.createView(context, VIEW_ID, mapOf("viewId" to VIEW_ID))
        }.exceptionOrNull()

        assertSame(failure, error)
        assertSame(cleanupFailure, failure.suppressed.single())
        assertNull(controller.session)
    }

    @Test
    fun `new session inherits resumed host without repeated activation for other views`() {
        val session = mock(ScannerSession::class.java)
        val controller = ScannerSessionController(
            mainHandler = mock(Handler::class.java),
            scanResultSink = ScanResultSink { _, _ -> },
            sessionFactory = { _, _ -> session },
        )
        controller.attachHostLifecycle(TestHostLifecycleOwner(Lifecycle.State.RESUMED).lifecycle)
        val context = mock(Context::class.java)

        controller.createView(context, VIEW_ID, mapOf("viewId" to VIEW_ID))
        controller.createView(context, VIEW_ID + 1, mapOf("viewId" to VIEW_ID + 1))

        verify(session, times(1)).activate()
        verify(session, never()).deactivate()
    }

    @Test
    fun `detach cannot pause a host attached reentrantly during observer removal`() {
        val firstHost = mock(Lifecycle::class.java)
        doReturn(Lifecycle.State.RESUMED).`when`(firstHost).currentState
        val secondHost = TestHostLifecycleOwner(Lifecycle.State.RESUMED)
        val session = mock(ScannerSession::class.java)
        val controller = controller().apply { setSession(session) }
        controller.attachHostLifecycle(firstHost)
        doAnswer {
            controller.attachHostLifecycle(secondHost.lifecycle)
            null
        }.`when`(firstHost).removeObserver(anyValue())
        clearInvocations(session)

        controller.detachHostLifecycle()

        assertEquals(1, secondHost.observerCount)
        verify(session, never()).deactivate()
    }

    @Test
    fun `reentrant host replacement cannot leave an obsolete observer attached`() {
        val firstHost = mock(Lifecycle::class.java)
        doReturn(Lifecycle.State.RESUMED).`when`(firstHost).currentState
        val secondHost = TestHostLifecycleOwner(Lifecycle.State.RESUMED)
        val thirdHost = TestHostLifecycleOwner(Lifecycle.State.STARTED)
        val session = mock(ScannerSession::class.java)
        val controller = controller().apply { setSession(session) }
        controller.attachHostLifecycle(firstHost)
        var replaced = false
        doAnswer {
            if (!replaced) {
                replaced = true
                controller.attachHostLifecycle(thirdHost.lifecycle)
            }
            null
        }.`when`(firstHost).removeObserver(anyValue())

        controller.attachHostLifecycle(secondHost.lifecycle)

        assertEquals(0, secondHost.observerCount)
        assertEquals(1, thirdHost.observerCount)
    }

    @Test
    fun `reattaching the same host synchronizes state without adding another observer`() {
        val host = mock(Lifecycle::class.java)
        doReturn(Lifecycle.State.RESUMED).`when`(host).currentState
        val session = mock(ScannerSession::class.java)
        val controller = controller().apply { setSession(session) }
        controller.attachHostLifecycle(host)
        doReturn(Lifecycle.State.STARTED).`when`(host).currentState

        controller.attachHostLifecycle(host)

        verify(host, times(1)).addObserver(anyValue())
        verify(session).activate()
        verify(session).deactivate()
    }

    @Test
    fun `attachment synchronizes the host selected during observer registration`() {
        val firstHost = TestHostLifecycleOwner(Lifecycle.State.RESUMED)
        val secondHost = TestHostLifecycleOwner(Lifecycle.State.STARTED)
        val session = mock(ScannerSession::class.java)
        val controller = controller().apply { setSession(session) }
        doAnswer {
            controller.attachHostLifecycle(secondHost.lifecycle)
            null
        }.`when`(session).activate()

        controller.attachHostLifecycle(firstHost.lifecycle)

        assertEquals(0, firstHost.observerCount)
        assertEquals(1, secondHost.observerCount)
        verify(session, times(1)).activate()
        verify(session, times(1)).deactivate()
    }

    @Test
    fun `attachment keeps session paused when host detaches during observer registration`() {
        val host = TestHostLifecycleOwner(Lifecycle.State.RESUMED)
        val session = mock(ScannerSession::class.java)
        val controller = controller().apply { setSession(session) }
        doAnswer {
            controller.detachHostLifecycle()
            null
        }.`when`(session).activate()

        controller.attachHostLifecycle(host.lifecycle)

        assertEquals(0, host.observerCount)
        verify(session, times(1)).activate()
        verify(session, times(1)).deactivate()
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
        fun <T> anyValue(): T = any<T>()
    }
}
