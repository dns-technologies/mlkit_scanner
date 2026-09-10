package com.dns_technologies.mlkit_scanner.scanner

import android.content.Context
import com.dns_technologies.mlkit_scanner.PluginError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock

internal class ScannerViewFactoryTest {
    @Test
    fun `create forwards context and view id without retaining settings`() {
        val context = mock(Context::class.java)
        val expectedView = mock(ScannerView::class.java)
        var captured: Pair<Context, Int>? = null
        val factory = ScannerViewFactory { actualContext, viewId ->
            captured = Pair(actualContext, viewId)
            expectedView
        }
        val arguments = mapOf("viewId" to 7, "initialZoomRatio" to 2.0)

        val view = factory.create(context, 7, arguments)

        assertSame(expectedView, view)
        assertSame(context, captured?.first)
        assertEquals(7, captured?.second)
    }

    @Test
    fun `create rejects a missing platform view context`() {
        var calls = 0
        val factory = ScannerViewFactory { _, _ ->
            calls++
            mock(ScannerView::class.java)
        }

        val error = assertThrows(IllegalArgumentException::class.java) {
            factory.create(null, 7, null)
        }

        assertEquals("Flutter did not provide a platform-view context", error.message)
        assertEquals(0, calls)
    }

    @Test
    fun `create rejects missing arguments before allocating resources`() {
        val context = mock(Context::class.java)
        val factory = ScannerViewFactory { _, _ -> error("must not create a view") }

        assertSame(PluginError.InvalidArguments, runCatching { factory.create(context, 9, null) }.exceptionOrNull())
    }

    @Test
    fun `create delegates each call without caching by view id`() {
        val context = mock(Context::class.java)
        val first = mock(ScannerView::class.java)
        val second = mock(ScannerView::class.java)
        val views = ArrayDeque(listOf(first, second))
        val factory = ScannerViewFactory { _, _ -> views.removeFirst() }

        assertSame(first, factory.create(context, 7, mapOf("viewId" to 7)))
        assertSame(second, factory.create(context, 7, mapOf("viewId" to 7)))
    }

    @Test
    fun `creation failure is returned unchanged to the platform host`() {
        val failure = IllegalStateException("Creation failed")
        val factory = ScannerViewFactory { _, _ -> throw failure }

        assertSame(failure,
            runCatching { factory.create(mock(Context::class.java), 7, mapOf("viewId" to 7)) }.exceptionOrNull())
    }
}
