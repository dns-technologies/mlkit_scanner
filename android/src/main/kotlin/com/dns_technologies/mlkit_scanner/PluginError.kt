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
) : Exception(message) {
    /** Camera initialization failed due to an internal camera error. */
    object InitCameraError : PluginError("1", "Internal camera initialisation error")

    /** The app has no granted camera permission. */
    object AuthorizationCameraError : PluginError("2", "The app does not have camera permission")

    /** A camera feature was requested before scanner initialization. */
    object CameraIsNotInitialized : PluginError("3", "Camera platform view is not created")

    /** The device does not expose a flash unit for the active camera. */
    object DeviceHasNotFlash : PluginError("4", "Device has no flash")

    /** The active camera does not support zoom control. */
    object DeviceHasNotZoom : PluginError("6", "Zoom is not supported on this device")

    /** Unexpected internal error while handling scanner operations. */
    object UnknownError : PluginError("7", "Unknown scanner error")

    /** The scanner session was released before the requested operation completed. */
    object CameraSessionDisposed : PluginError("8", "Camera session has been disposed")
}
