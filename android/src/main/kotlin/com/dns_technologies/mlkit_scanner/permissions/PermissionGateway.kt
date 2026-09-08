package com.dns_technologies.mlkit_scanner.permissions

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Owns a queue of hardware permission requests, with at most one Android request in flight.
 * Callers requesting the same permission share its result, including while it is queued.
 *
 * All entry points run on the main thread, including coroutine calls and their resumptions.
 * The plugin owns listener registration; this class retains an Activity only while attached.
 * Configuration changes preserve requests, whereas final detach completes them as denied.
 */
@MainThread
internal class PermissionGateway(
    private val permissionChecker: (Activity, String) -> Boolean = { activity, permission ->
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    },
    private val permissionRequester: (Activity, String, Int) -> Unit = { activity, permission, requestCode ->
        ActivityCompat.requestPermissions(activity, arrayOf(permission), requestCode)
    },
) {
    private var activity: Activity? = null
    private val pendingRequests = linkedMapOf<String, PermissionRequest>()

    /** Rechecks the outstanding request and resumes queued work on the new Activity. */
    fun attach(activity: Activity) {
        this.activity = activity
        pendingRequests.values.firstOrNull()?.let { request ->
            if (permissionChecker(activity, request.permission)) complete(request, isGranted = true)
        }
        dispatchNext()
    }

    /** Releases the old Activity without interrupting the system request or its callers. */
    fun detachForConfigChange() {
        activity = null
    }

    /** Releases all owned requests before resuming their callers; repeated detach is harmless. */
    fun detachFinal() {
        activity = null
        val requests = pendingRequests.values.toList()
        pendingRequests.clear()
        requests.forEach { it.result.complete(false) }
    }

    /** Requests camera access using the common single-permission flow. */
    suspend fun requestCameraPermission(): Boolean =
        requestPermission(Manifest.permission.CAMERA, requestCode = 0)

    /**
     * Checks [permission] or joins/enqueues its request. Each named helper supplies its own fixed
     * [requestCode] and an OS-supported hardware permission declared in the manifest.
     * Codes must differ between permissions and other request owners in the same Activity.
     * Internal visibility allows testing future permission flows without adding feature APIs.
     *
     * Different permissions are requested sequentially. Cancelling one caller does not cancel
     * another caller's wait. A queued request with no active callers is discarded; a dispatched
     * request stays owned until its callback or final detach because Android cannot cancel it.
     * During configuration detach, callers can join existing requests but cannot create new ones.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun requestPermission(permission: String, requestCode: Int): Boolean {
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        require(permission.isNotBlank()) { "Permission name must not be blank" }
        val currentActivity = activity
        if (currentActivity != null && permissionChecker(currentActivity, permission)) return true
        val request = pendingRequests[permission] ?: run {
            if (currentActivity == null) return false
            // No suspension between looking up a request and publishing its owner.
            PermissionRequest(permission, requestCode).also { pendingRequests[permission] = it }
        }
        val caller = callerContext[Job]
        request.callers += caller
        return try {
            dispatchNext()
            request.result.await()
        } finally {
            request.callers.remove(caller)
            if (!request.isDispatched && !request.hasActiveCallers) {
                complete(request, isGranted = false)
                dispatchNext()
            }
        }
    }

    /** Consumes only the dispatched request's callback; malformed or interrupted results deny it. */
    fun onPermissionResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ): Boolean {
        val request = pendingRequests.values.firstOrNull() ?: return false
        if (!request.isDispatched || requestCode != request.requestCode) return false
        val isGranted = if (permissions.isEmpty() && grantResults.isEmpty()) {
            false
        } else {
            if (permissions.singleOrNull() != request.permission) return false
            // During configuration detach, this complete single-permission result is sufficient.
            // With an Activity, recheck the requested permission rather than trusting stale data.
            when (val result = grantResults.singleOrNull()) {
                PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_DENIED ->
                    activity?.let { permissionChecker(it, request.permission) }
                        ?: (result == PackageManager.PERMISSION_GRANTED)
                else -> false
            }
        }
        complete(request, isGranted)
        dispatchNext()
        return true
    }

    /**
     * Each pass removes the head or returns with a dispatched request or no Activity.
     * Read the live head: completing a request can resume callers that change the queue.
     */
    private fun dispatchNext() {
        while (pendingRequests.isNotEmpty()) {
            val currentActivity = activity ?: return
            val request = pendingRequests.values.first()
            if (request.isDispatched) return
            if (!request.hasActiveCallers) {
                complete(request, isGranted = false)
                continue
            }
            if (permissionChecker(currentActivity, request.permission)) {
                complete(request, isGranted = true)
                continue
            }
            try {
                request.dispatch(currentActivity, permissionRequester)
                return
            } catch (error: CancellationException) {
                if (remove(request)) request.result.cancel(error)
            } catch (_: RuntimeException) {
                complete(request, isGranted = false)
            }
        }
    }

    private fun complete(request: PermissionRequest, isGranted: Boolean) {
        if (!remove(request)) return
        request.result.complete(isGranted)
    }

    private fun remove(request: PermissionRequest): Boolean {
        // An old caller's cleanup must not remove a replacement under the same permission key.
        if (pendingRequests[request.permission] !== request) return false
        pendingRequests.remove(request.permission)
        return true
    }

    private class PermissionRequest(val permission: String, val requestCode: Int) {
        // No parent Job: caller cancellation must not cancel the shared Android request.
        val result = CompletableDeferred<Boolean>()
        val callers = mutableListOf<Job?>()
        // Check jobs directly: cancellation can precede execution of a caller's finally block.
        val hasActiveCallers: Boolean get() = callers.any { it?.isActive != false }
        var isDispatched = false
            private set

        fun dispatch(activity: Activity, requester: (Activity, String, Int) -> Unit) {
            // Publish correlation before Android can invoke a synchronous callback.
            isDispatched = true
            requester(activity, permission, requestCode)
        }
    }
}
