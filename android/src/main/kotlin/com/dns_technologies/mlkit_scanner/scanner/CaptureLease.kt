package com.dns_technologies.mlkit_scanner.scanner

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.commands.base.BaseScannerCommand
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * An actual camera-control lease and the work that is cancelled when it closes.
 *
 * @property consumer Consumer that exclusively owns this capture lifetime.
 */
internal class CaptureLease(val consumer: ScannerConsumer, val id: String) {
    /** Main-thread supervisor cancelled when capture ownership is revoked. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Child lease that owns recognition result delivery. */
    var scanLease: ScanLease? = null
    /** Configuration work that a repeat capture can supersede under the same ID. */
    private var captureWork: Job? = null
    val configurationInProgress: Boolean get() = captureWork?.isCompleted == false
    var configured = false
        private set
    /** Whether this lifetime has been permanently revoked. */
    var closed = false
        private set

    /** Unfinished channel replies that must fail when the lease closes. */
    private val replies = mutableSetOf<PendingReply>()

    /** Registers an at-most-once reply and removes it when completed. */
    fun reply(result: MethodChannel.Result): PendingReply =
        PendingReply(result) { replies.remove(it) }.also { replies += it }

    /** Replaces and cancels prior configuration work before starting the new work. */
    fun launchCapture(reply: PendingReply, action: suspend CoroutineScope.() -> Unit) {
        // Register the new job first so completion of the previous job cannot clear it.
        val next = scope.launch(start = CoroutineStart.LAZY, block = action)
        val previous = captureWork
        captureWork = next
        configured = false
        next.invokeOnCompletion { cause ->
            if (captureWork === next) captureWork = null
            if (cause is CancellationException) {
                BaseScannerCommand.reportScannerError(reply, PluginError.CameraSessionDisposed)
            }
        }
        previous?.cancel()
        next.start()
    }

    fun markConfigured() {
        configured = true
    }

    /** Replaces the result lease, permanently closing the previous endpoint. */
    fun startScan(id: String, periodMs: Int): ScanLease {
        if (id.isEmpty()) throw PluginError.InvalidArguments
        scanLease?.close()
        return ScanLease(id, periodMs).also { scanLease = it }
    }

    /** Permanently revokes this lifetime and its outstanding work. */
    fun close() {
        if (closed) return
        closed = true
        configured = false
        scanLease?.close()
        scanLease = null
        scope.cancel()
        replies.toList().forEach { BaseScannerCommand.reportScannerError(it, PluginError.CameraSessionDisposed) }
    }
}

/** Recognition result lease with permanent revocation of delivery. */
internal class ScanLease(val id: String, var periodMs: Int) {
    /** Whether barcode delivery is enabled for this subscription. */
    var enabled = false
    /** Whether this subscription has been permanently revoked. */
    var closed = false
        private set

    /** Permanently disables barcode delivery for this subscription. */
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
