package com.dns_technologies.mlkit_scanner

/**
 * Describes plugin errors that can be sent to Dart.
 */
enum class PluginError(val errorCode: String) {
    /** Camera initialization failed due to an internal camera error. */
    InitCameraError("1"),

    /** The app has no granted camera permission. */
    AuthorizationCameraError("2"),

    /** A camera feature was requested before scanner initialization. */
    CameraIsNotInitialized("3"),

    /** The device does not expose a flash unit for the active camera. */
    DeviceHasNotFlash("4"),

    /** A method channel call received arguments with an unexpected type or shape. */
    InvalidArguments("5"),

    /** The active camera does not support zoom control. */
    DeviceHasNotZoom("6"),
}
