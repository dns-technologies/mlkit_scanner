package com.dns_technologies.mlkit_scanner.scanner

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.commands.base.reportScannerError
import io.flutter.plugin.common.MethodChannel
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * An actual camera-control lease and the work that is cancelled when it closes.
 *
 * @property consumer Registered consumer that exclusively owns this capture lifetime.
 */
internal class CaptureLease(val consumer: ScannerConsumer) {
    /** Unique identifier of this lifetime. */
    val id = UUID.randomUUID().toString()
    /** Main-thread supervisor cancelled when capture ownership is revoked. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Current recognition endpoint prepared for this capture lease. */
    var subscription: ResultEndpoint? = null
    /** Whether this lifetime has been permanently revoked. */
    var closed = false
        private set

    /** Unfinished channel replies that must fail when the lease closes. */
    private val replies = mutableSetOf<PendingReply>()

    /** Registers an at-most-once reply and removes it when completed. */
    fun reply(result: MethodChannel.Result): PendingReply =
        PendingReply(result) { replies.remove(it) }.also { replies += it }

    /** Permanently revokes this lifetime and its outstanding work. */
    fun close() {
        if (closed) return
        closed = true
        subscription?.close()
        subscription = null
        scope.cancel()
        replies.toList().forEach { reportScannerError(it, PluginError.CameraSessionDisposed) }
    }
}

/** Cancellable result endpoint with an explicit delivery-enabled state. */
internal class ResultEndpoint {
    /** Unique identifier of this lifetime. */
    val id = UUID.randomUUID().toString()
    /** Whether result delivery is enabled for this endpoint. */
    var enabled = false
    /** Whether this lifetime has been permanently revoked. */
    var closed = false
        private set

    /** Permanently revokes this lifetime and its outstanding work. */
    fun close() {
        closed = true
        enabled = false
    }
}

/** Completes each platform call at most once. */
internal class PendingReply(
    /** Original Flutter reply completed by the winning response path. */
    private val target: MethodChannel.Result,
    /** Completion callback invoked before delivering the response. */
    private val onComplete: (PendingReply) -> Unit,
) : MethodChannel.Result {
    /** Prevents repeated response delivery. */
    private var completed = false

    /** Delivers a response at most once, including during reentrant callbacks. */
    private fun complete(action: () -> Unit) {
        if (completed) return
        completed = true
        onComplete(this)
        action()
    }

    override fun success(value: Any?) = complete { target.success(value) }

    override fun error(code: String, message: String?, details: Any?) = complete {
        target.error(code, message, details)
    }

    override fun notImplemented() = complete { target.notImplemented() }
}
