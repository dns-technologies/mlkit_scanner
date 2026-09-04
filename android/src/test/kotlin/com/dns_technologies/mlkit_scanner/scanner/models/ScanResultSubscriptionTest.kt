package com.dns_technologies.mlkit_scanner.scanner.models

import org.junit.Assert.assertEquals
import org.junit.Test

internal class ScanResultSubscriptionTest {
    @Test
    fun `cancel releases subscription once`() {
        var cancellationCalls = 0
        val subscription = ScanResultSubscription { cancellationCalls += 1 }

        subscription.cancel()
        subscription.cancel()

        assertEquals(1, cancellationCalls)
    }
}
