package com.dns_technologies.mlkit_scanner

/**
 * Describes plugin errors that can be sent to Dart and propagated as exceptions.
 *
 * @property errorCode Method channel error code exposed to Flutter.
 * @property message Human-readable error message.
 */
internal sealed class PluginError(
    val errorCode: String,
    override val message: String,
    cause: Throwable? = null,
    /** Optional StandardMessageCodec-compatible diagnostic payload sent to Dart. */
    val details: Any? = null,
) : Exception(message, cause) {
    /** The app has no granted camera permission. */
    object AuthorizationCameraError : PluginError("2", "The app does not have camera permission")

    /** A camera feature was requested before scanner initialization. */
    object CameraIsNotInitialized : PluginError("3", "Camera platform view is not created")

    /** The device does not expose a flash unit for the active camera. */
    object DeviceHasNotFlash : PluginError("4", "Device has no flash")

    /** Flutter supplied a malformed or incomplete command payload. */
    object InvalidArguments : PluginError("5", "Invalid scanner arguments")

    /** The active camera does not support zoom control. */
    object DeviceHasNotZoom : PluginError("6", "Zoom is not supported on this device")

    /** Unexpected internal error while handling scanner operations. */
    object UnknownError : PluginError("7", "Unknown scanner error")

    /** The scanner session was released before the requested operation completed. */
    object CameraSessionDisposed : PluginError("8", "Camera session has been disposed")

    /** An asynchronous camera control operation failed. */
    class CameraControlError(
        /** Camera operation whose asynchronous completion failed. */
        val operation: CameraControlOperation,
        /** Logical widget that owned the failed operation, when known. */
        val viewId: Int? = null,
        cause: Throwable? = null,
        /** Original state error code reported by the camera, when readiness failed. */
        val cameraStateErrorCode: Int? = null,
    ) :
        PluginError(
            errorCode = ERROR_CODE,
            message = ERROR_MESSAGE,
            cause = cause,
            details =
                mapOf(
                    "operation" to operation.wireValue,
                    "viewId" to viewId,
                    "cause" to cause?.toChannelDetails(),
                    "cameraStateErrorCode" to cameraStateErrorCode,
                ),
        ) {
        /** Adds session context without losing the original native failure. */
        fun contextualize(operation: CameraControlOperation, viewId: Int): CameraControlError =
            CameraControlError(
                operation = operation,
                viewId = viewId,
                cause = cause,
                cameraStateErrorCode = cameraStateErrorCode,
            )

        internal companion object {
            /** Stable channel code identifying camera-control failures. */
            const val ERROR_CODE = "9"
            /** Shared public message for contextualized camera-control failures. */
            const val ERROR_MESSAGE = "Camera control operation failed"
        }
    }
}

/**
 * Camera operation attached to error code 9 and sent to Dart.
 *
 * @property wireValue Stable operation name serialized in channel error details.
 */
internal enum class CameraControlOperation(val wireValue: String) {
    /** Waiting for the camera device to become ready. */
    AWAIT_OPEN("awaitOpen"),
    /** Applying an absolute zoom ratio. */
    ZOOM("zoom"),
    /** Applying the desired flash illumination state. */
    TORCH("torch"),
    /** Updating or resetting camera focus metering. */
    FOCUS("focus"),
}

/** Converts an original native failure to StandardMessageCodec-compatible details. */
private fun Throwable.toChannelDetails(): Map<String, String?> =
    mapOf("type" to javaClass.name, "message" to message, "stackTrace" to stackTraceToString())
