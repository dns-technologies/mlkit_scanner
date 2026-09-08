package com.dns_technologies.mlkit_scanner.utils

import com.dns_technologies.mlkit_scanner.PluginError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

internal class ArgumentUtilsTest {
    @Test
    fun `integer parsing accepts exact numeric representations`() {
        listOf<Number>(42, 42L, 42.0, 42F, 42.toShort(), 42.toByte()).forEach { value ->
            assertEquals(42, mapOf("value" to value).requireInt("value"))
        }
        assertEquals(0, mapOf("value" to -0.0).requireInt("value"))
    }

    @Test
    fun `integer parsing preserves both Int boundaries`() {
        listOf(Int.MIN_VALUE, Int.MAX_VALUE).forEach { boundary ->
            listOf<Number>(boundary, boundary.toLong(), boundary.toDouble()).forEach { value ->
                assertEquals(boundary, mapOf("value" to value).requireInt("value"))
            }
        }
    }

    @Test
    fun `integer parsing rejects fractional numbers instead of truncating them`() {
        listOf(
            1.5, -1.5, 150.5, Double.MIN_VALUE, -Double.MIN_VALUE,
            0.5F, Int.MAX_VALUE - 0.5, Int.MIN_VALUE + 0.5,
        ).forEach(::assertInvalidInt)
    }

    @Test
    fun `integer parsing rejects overflow instead of wrapping or clamping`() {
        listOf(
            Int.MAX_VALUE.toLong() + 1, Int.MIN_VALUE.toLong() - 1,
            Long.MAX_VALUE, Long.MIN_VALUE, 4_294_967_296L,
            Int.MAX_VALUE.toDouble() + 1, Int.MIN_VALUE.toDouble() - 1,
            Double.MAX_VALUE, -Double.MAX_VALUE, Int.MAX_VALUE.toFloat(),
        ).forEach(::assertInvalidInt)
    }

    @Test
    fun `integer parsing rejects nonfinite values and nonnumeric inputs`() {
        listOf(
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            null, "42", true, listOf(42), emptyMap<String, Any?>(),
        ).forEach(::assertInvalidInt)
        assertSame(
            PluginError.InvalidArguments,
            runCatching { emptyMap<String, Any?>().requireInt("value") }.exceptionOrNull(),
        )
    }

    private fun assertInvalidInt(value: Any?) {
        assertSame(
            "Expected InvalidArguments for $value",
            PluginError.InvalidArguments,
            runCatching { mapOf("value" to value).requireInt("value") }.exceptionOrNull(),
        )
    }
}
