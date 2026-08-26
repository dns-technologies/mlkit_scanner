package com.dns_technologies.mlkit_scanner.scanner.components.ui.focus

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

internal class FocusControllerTest {
    @Test
    fun `focus request uses actual center of offset visor rectangle`() {
        val focusView = mock(FocusView::class.java)
        var autoFocusCallback: (() -> Unit)? = null
        doAnswer { invocation ->
            autoFocusCallback = invocation.getArgument(0)
            null
        }.`when`(focusView).onAutoFocusRequested = any()
        val controller = FocusController(focusView)
        var requestedOffset: Pair<Float, Float>? = null
        controller.bind(
            onAutoFocusRequest = { x, y -> requestedOffset = Pair(x, y) },
            onLockedFocusRequest = { _, _ -> },
        )
        controller.updateCenter(50F, -20F)

        autoFocusCallback?.invoke()

        val offset = requireNotNull(requestedOffset)
        assertEquals(50F, offset.first, 0.0001F)
        assertEquals(-20F, offset.second, 0.0001F)
    }

    @Test
    fun `new center updates visual focus offset`() {
        val focusView = mock(FocusView::class.java)
        val controller = FocusController(focusView)

        controller.updateCenter(50F, -25F)
        controller.updateCenter(100F, -50F)

        verify(focusView).setCenterOffset(50F, -25F)
        verify(focusView).setCenterOffset(100F, -50F)
    }

    @Test
    fun `dispose removes focus callbacks`() {
        val focusView = mock(FocusView::class.java)
        val controller = FocusController(focusView)

        controller.dispose()

        verify(focusView).onAutoFocusRequested = null
        verify(focusView).onLockFocusRequested = null
    }
}
