package com.dns_technologies.mlkit_scanner.scanner.models

/** Handle used to stop receiving scanner results. */
class ScanResultSubscription internal constructor(
    private val onCancel: () -> Unit,
) {
    /** Stops delivering scan results to the listener associated with this subscription. */
    fun cancel() {
        onCancel.invoke()
    }
}
