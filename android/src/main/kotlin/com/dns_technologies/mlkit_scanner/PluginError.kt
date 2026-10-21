package com.dns_technologies.mlkit_scanner

/**
 * Scanner failures with stable error codes and diagnostic details.
 *
 * @property errorCode Stable error code for channel responses.
 * @property message Human-readable error message.
 */
internal sealed class PluginError(
    val errorCode: String,
    override val message: String,
    cause: Throwable? = null,
    /** Optional diagnostic payload containing codec-compatible values. */
    val details: Any? = null,
) : Exception(message, cause) {
    /** The app has no granted camera permission. */
    object AuthorizationCameraError : PluginError("2", "The app does not have camera permission")

    /** A camera feature was requested before scanner initialization. */
    object CameraIsNotInitialized : PluginError("3", "Camera platform view is not created")

    /** The device does not expose a flash unit for the active camera. */
    object DeviceHasNotFlash : PluginError("4", "Device has no flash")

    /** A command payload is malformed or incomplete. */
    object InvalidArguments : PluginError("5", "Invalid scanner arguments")

    /** The active camera does not support zoom control. */
    object DeviceHasNotZoom : PluginError("6", "Zoom is not supported on this device")

    /** Unexpected internal error while handling scanner operations. */
    object UnknownError : PluginError("7", "Unknown scanner error")

    /** The scanner session was released before the requested operation completed. */
    object CameraSessionDisposed : PluginError("8", "Camera session has been disposed")

    /** Camera failure described at the point where it occurred. */
    class CameraError(
        message: String,
        /** Consumer identifier associated with the failed operation, when known. */
        val viewId: Int? = null,
        cause: Throwable? = null,
        /** Original state error code reported by the camera, when readiness failed. */
        val cameraStateErrorCode: Int? = null,
    ) :
        PluginError(
            errorCode = ERROR_CODE,
            message = message,
            cause = cause,
            details =
                mapOf(
                    "viewId" to viewId,
                    "cause" to cause?.toChannelDetails(),
                    "cameraStateErrorCode" to cameraStateErrorCode,
                ),
        ) {
        /** Adds session context without losing the original native failure. */
        fun contextualize(viewId: Int): CameraError =
            CameraError(
                message = message,
                viewId = viewId,
                cause = cause,
                cameraStateErrorCode = cameraStateErrorCode,
            )

        internal companion object {
            /** Stable channel code identifying camera failures. */
            const val ERROR_CODE = "9"
        }
    }
}

/** Converts an original native failure to StandardMessageCodec-compatible details. */
private fun Throwable.toChannelDetails(): Map<String, String?> =
    mapOf("type" to javaClass.name, "message" to message, "stackTrace" to stackTraceToString())
