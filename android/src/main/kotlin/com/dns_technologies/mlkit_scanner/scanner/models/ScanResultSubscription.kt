package com.dns_technologies.mlkit_scanner.scanner.models

import java.util.concurrent.atomic.AtomicBoolean

/** Handle used to stop receiving scanner results. */
class ScanResultSubscription internal constructor(
    private val onCancel: () -> Unit,
) {
    private val isCancelled = AtomicBoolean(false)

    /** Stops delivering scan results to the listener associated with this subscription. */
    fun cancel() {
        if (isCancelled.compareAndSet(false, true)) onCancel.invoke()
    }
}
