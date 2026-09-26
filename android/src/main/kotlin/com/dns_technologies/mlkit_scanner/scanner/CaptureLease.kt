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
 * @property consumer Registered widget that exclusively owns this capture lifetime.
 */
internal class CaptureLease(val consumer: ScannerConsumer) {
    /** Opaque identity echoed by Dart to correlate this lifetime's messages. */
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

/** A real event endpoint, prepared before Dart enables frame analysis. */
internal class ResultEndpoint {
    /** Opaque identity echoed by Dart to correlate this lifetime's messages. */
    val id = UUID.randomUUID().toString()
    /** Whether Dart has enabled result delivery for this endpoint. */
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

/** Completes each platform call once, including cancellation racing SDK completion. */
internal class PendingReply(
    /** Original Flutter reply completed by the winning response path. */
    private val target: MethodChannel.Result,
    /** Removes this reply from its lease before invoking Flutter callbacks. */
    private val onComplete: (PendingReply) -> Unit,
) : MethodChannel.Result {
    /** Prevents cancellation and SDK completion from replying twice. */
    private var completed = false

    /** Claims completion before invoking potentially reentrant callbacks. */
    private fun complete(action: () -> Unit) {
        if (completed) return
        completed = true
        onComplete(this)
        action()
    }

    /** Completes the original reply successfully at most once. */
    override fun success(value: Any?) = complete { target.success(value) }

    /** Completes the original reply with a channel error at most once. */
    override fun error(code: String, message: String?, details: Any?) = complete {
        target.error(code, message, details)
    }

    /** Completes the original reply as unsupported at most once. */
    override fun notImplemented() = complete { target.notImplemented() }
}
