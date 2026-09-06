package com.dns_technologies.mlkit_scanner.scanner

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock

internal class ScannerViewFactoryTest {
    @Test
    fun `create forwards context view id and creation arguments`() {
        val context = mock(Context::class.java)
        val expectedView = mock(ScannerView::class.java)
        var captured: Triple<Context, Int, Any?>? = null
        val factory = ScannerViewFactory { actualContext, viewId, arguments ->
            captured = Triple(actualContext, viewId, arguments)
            expectedView
        }
        val arguments = mapOf("initialZoomRatio" to 2.0)

        val view = factory.create(context, 7, arguments)

        assertSame(expectedView, view)
        assertSame(context, captured?.first)
        assertEquals(7, captured?.second)
        assertSame(arguments, captured?.third)
    }

    @Test
    fun `create rejects a missing platform view context`() {
        var calls = 0
        val factory = ScannerViewFactory { _, _, _ ->
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
    fun `create passes null arguments without interpreting them`() {
        val context = mock(Context::class.java)
        val expected = mock(ScannerView::class.java)
        val factory = ScannerViewFactory { actualContext, viewId, arguments ->
            assertSame(context, actualContext)
            assertEquals(9, viewId)
            assertEquals(null, arguments)
            expected
        }

        assertSame(expected, factory.create(context, 9, null))
    }

    @Test
    fun `create delegates each call without caching by view id`() {
        val context = mock(Context::class.java)
        val first = mock(ScannerView::class.java)
        val second = mock(ScannerView::class.java)
        val views = ArrayDeque(listOf(first, second))
        val factory = ScannerViewFactory { _, _, _ -> views.removeFirst() }

        assertSame(first, factory.create(context, 7, null))
        assertSame(second, factory.create(context, 7, null))
    }

    @Test
    fun `creation failure is returned unchanged to the platform host`() {
        val failure = IllegalStateException("Creation failed")
        val factory = ScannerViewFactory { _, _, _ -> throw failure }

        assertSame(failure,
            runCatching { factory.create(mock(Context::class.java), 7, null) }.exceptionOrNull())
    }
}
