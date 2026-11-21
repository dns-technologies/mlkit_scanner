package com.dns_technologies.mlkit_scanner.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

internal class ExceptionCollectorTest {
    @Test
    fun `successful actions need no exception handling`() {
        val failures = ExceptionCollector()
        failures.throwIfFailed()

        var calls = 0
        repeat(3) { failures.attempt { calls++ } }

        failures.throwIfFailed()
        assertEquals(3, calls)
    }

    @Test
    fun `failure is deferred until every independent action has run`() {
        val failures = ExceptionCollector()
        val error = IllegalStateException("first")
        val calls = mutableListOf<Int>()

        failures.attempt { calls += 1; throw error }
        failures.attempt { calls += 2 }

        assertEquals(listOf(1, 2), calls)
        assertSame(error, runCatching { failures.throwIfFailed() }.exceptionOrNull())
    }

    @Test
    fun `later exceptions are suppressed in execution order`() {
        val failures = ExceptionCollector()
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        val third = UnsupportedOperationException("third")

        listOf(first, second, third).forEach { error -> failures.attempt { throw error } }

        assertSame(first, runCatching { failures.throwIfFailed() }.exceptionOrNull())
        assertEquals(listOf(second, third), first.suppressed.toList())
    }

    @Test
    fun `repeated primary exception is not suppressed onto itself`() {
        val failures = ExceptionCollector()
        val error = IllegalStateException("shared")

        repeat(2) { failures.attempt { throw error } }

        assertSame(error, runCatching { failures.throwIfFailed() }.exceptionOrNull())
        assertEquals(0, error.suppressed.size)
    }

    @Test
    fun `rollback keeps the original error and suppresses cleanup failures`() {
        val original = IllegalStateException("operation failed")
        val first = IllegalArgumentException("first cleanup")
        val second = UnsupportedOperationException("second cleanup")
        val failures = ExceptionCollector(original)
        var cleaned = false

        failures.attempt { throw first }
        failures.attempt { throw original }
        failures.attempt { cleaned = true }
        failures.attempt { throw second }

        assertEquals(true, cleaned)
        assertSame(original, runCatching { failures.throwIfFailed() }.exceptionOrNull())
        assertEquals(listOf(first, second), original.suppressed.toList())
    }

    @Test
    fun `errors propagate immediately instead of being collected`() {
        val failures = ExceptionCollector()
        val error = AssertionError("fatal")

        assertSame(error, runCatching { failures.attempt { throw error } }.exceptionOrNull())
        failures.throwIfFailed()
    }
}
