package com.dns_technologies.mlkit_scanner

/**
 * Plugin error enum with associating error code
 *
 * Used for transfer errors between android platform and flutter
 */
enum class PluginError(val errorCode: String) {
    /** Unable to initialize camera due to internal camera error */
    InitCameraError("1"),
    /** App has no user permission for camera using */
    AuthorizationCameraError("2"),
    /**
     * Occur when app try to use camera features (image analysing, flash toggle, etc)
     * without calling initialization method
     */
    CameraIsNotInitialized("3"),
    /** Occurs due to try toggle flash on device that does not support flash */
    DeviceHasNotFlash("4"),
    /** Occurs due to transfer incorrect method call arguments */
    InvalidArguments("5"),
    /** Occurs due to try use zoom on device that does not support zoom */
    DeviceHasNotZoom("6"),
}