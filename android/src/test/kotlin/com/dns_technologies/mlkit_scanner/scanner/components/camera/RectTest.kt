package com.dns_technologies.mlkit_scanner.scanner.components.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RectTest {
    @Test
    fun `dimensions use exclusive right and bottom bounds`() {
        val rect = Rect(left = 10, top = 20, right = 110, bottom = 70)

        assertEquals(100, rect.width)
        assertEquals(50, rect.height)
        assertFalse(rect.isEmpty)
    }

    @Test
    fun `zero or negative dimension makes rectangle empty`() {
        assertTrue(Rect(10, 0, 10, 20).isEmpty)
        assertTrue(Rect(20, 0, 10, 20).isEmpty)
        assertTrue(Rect(0, 10, 20, 10).isEmpty)
        assertTrue(Rect(0, 20, 20, 10).isEmpty)
    }
}
