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
        val factory = ScannerViewFactory { _, _, _ -> mock(ScannerView::class.java) }

        val error = assertThrows(IllegalArgumentException::class.java) {
            factory.create(null, 7, null)
        }

        assertEquals("Flutter did not provide a platform-view context", error.message)
    }
}
