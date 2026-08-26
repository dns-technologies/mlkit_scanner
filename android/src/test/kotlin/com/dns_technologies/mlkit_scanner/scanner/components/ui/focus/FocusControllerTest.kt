package com.dns_technologies.mlkit_scanner.scanner.components.ui.focus

import android.view.View
import android.widget.FrameLayout
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

internal class FocusControllerTest {
    @Test
    fun `zero bounds register one layout listener and apply latest offset after layout`() {
        val boundsView = mock(FrameLayout::class.java)
        val focusView = mock(FocusView::class.java)
        doReturn(0).`when`(boundsView).width
        doReturn(0).`when`(boundsView).height
        val controller = FocusController(
            boundsView,
            focusView,
        )

        controller.updateCenter(0.25F, -0.5F)
        controller.updateCenter(0.5F, -0.25F)

        val listenerCaptor = ArgumentCaptor.forClass(View.OnLayoutChangeListener::class.java)
        verify(boundsView, times(1)).addOnLayoutChangeListener(listenerCaptor.capture())
        verify(focusView, never()).setCenterOffset(50.0F, -25.0F)

        doReturn(200).`when`(boundsView).width
        doReturn(100).`when`(boundsView).height
        listenerCaptor.value.onLayoutChange(boundsView, 0, 0, 200, 100, 0, 0, 0, 0)

        verify(boundsView).removeOnLayoutChangeListener(listenerCaptor.value)
        verify(focusView).setCenterOffset(50.0F, -12.5F)
    }

    @Test
    fun `dispose removes pending layout listener and focus callbacks`() {
        val boundsView = mock(FrameLayout::class.java)
        val focusView = mock(FocusView::class.java)
        doReturn(0).`when`(boundsView).width
        doReturn(0).`when`(boundsView).height
        val controller = FocusController(boundsView, focusView)
        controller.updateCenter(0.5F, 0.5F)
        val listenerCaptor = ArgumentCaptor.forClass(View.OnLayoutChangeListener::class.java)
        verify(boundsView).addOnLayoutChangeListener(listenerCaptor.capture())

        controller.dispose()

        verify(boundsView).removeOnLayoutChangeListener(listenerCaptor.value)
        verify(focusView).onAutoFocusRequested = null
        verify(focusView).onLockFocusRequested = null
    }
}
