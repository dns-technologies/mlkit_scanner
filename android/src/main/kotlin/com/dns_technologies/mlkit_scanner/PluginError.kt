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
    val details: Any? = null,
) : Exception(message, cause) {
    /** Camera initialization failed due to an internal camera error. */
    object InitCameraError : PluginError("1", "Internal camera initialisation error")

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
        val operation: CameraControlOperation,
        val viewId: Int? = null,
        cause: Throwable? = null,
        val cameraStateErrorCode: Int? = null,
    ) : PluginError(
        errorCode = ERROR_CODE,
        message = ERROR_MESSAGE,
        cause = cause,
        details = mapOf(
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
            const val ERROR_CODE = "9"
            const val ERROR_MESSAGE = "Camera control operation failed"
        }
    }
}

/** Camera operation attached to error code 9 and sent to Dart. */
internal enum class CameraControlOperation(val wireValue: String) {
    AWAIT_OPEN("awaitOpen"),
    ZOOM("zoom"),
    TORCH("torch"),
    FOCUS("focus"),
}

/** Converts an original native failure to StandardMessageCodec-compatible details. */
private fun Throwable.toChannelDetails(): Map<String, String?> = mapOf(
    "type" to javaClass.name,
    "message" to message,
    "stackTrace" to stackTraceToString(),
)
