package com.dns_technologies.mlkit_scanner

/** Contains method channel names used by the Dart and Android plugin sides. */
internal object PluginConstants {
    /** Argument key identifying the platform view that owns a command or event. */
    const val viewIdArgument = "viewId"

    /** Event key containing the recognized barcode payload. */
    const val barcodeArgument = "barcode"

    /** Optional view-registration argument containing normalized zoom. */
    const val initialZoomArgument = "initialZoom"

    /** Optional view-registration argument containing the initial torch state. */
    const val initialFlashEnabledArgument = "initialFlashEnabled"

    /** Optional view-registration argument containing the recognition area. */
    const val initialCropRectArgument = "initialCropRect"

    /** Argument key containing a camera-control value. */
    const val valueArgument = "value"

    /** Argument key containing a scan cooldown. */
    const val delayArgument = "delay"

    /** Argument key containing a recognition area. */
    const val cropRectArgument = "cropRect"

    /** Method channel name used for scanner commands and events. */
    const val channelName = "mlkit_channel"

    /** Platform view type name registered for the native camera preview. */
    const val cameraPlatformViewName = "mlkit/camera_preview"

    /** Method name used to transfer camera ownership to one registered platform view. */
    const val captureCameraMethod = "captureCamera"

    /** Method name used to release camera ownership held by one platform view. */
    const val releaseCameraMethod = "releaseCamera"

    /** Method name used to select and resume a platform view's camera preview. */
    const val resumeCameraMethod = "resumeCameraMethod"

    /** Method name used to pause the camera preview lifecycle. */
    const val pauseCameraMethod = "pauseCameraMethod"

    /** Method name used to toggle the camera torch. */
    const val toggleFlashMethod = "toggleFlash"

    /** Method name used to start barcode analysis. */
    const val startScanMethod = "startScan"

    /** Method name used to stop barcode analysis. */
    const val cancelScanMethod = "cancelScan"

    /** Method name used to update the cooldown after successful recognition. */
    const val setScanDelayMethod = "setScanDelay"

    /** Method name used to deliver recognized barcode values to Dart. */
    const val scanResultMethod = "onScanResult"

    /** Method name used to update camera zoom. */
    const val setZoomMethod = "setZoom"

    /** Method name used to update the recognized visor crop area. */
    const val setCropAreaMethod = "setCropAreaMethod"
}
