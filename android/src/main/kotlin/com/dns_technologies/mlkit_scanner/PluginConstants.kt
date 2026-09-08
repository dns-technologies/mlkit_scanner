package com.dns_technologies.mlkit_scanner

/** Contains method channel names used by the Dart and Android plugin sides. */
internal object PluginConstants {
    /** Shared tag used by Android scanner components. */
    const val LOG_TAG = "MLKIT_SCANNER_PLUGIN"

    /** Argument key identifying the platform view that owns a command or event. */
    const val viewIdArgument = "viewId"

    /** Event key containing the recognized barcode payload. */
    const val barcodeArgument = "barcode"

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


    const val setZoomRatioMethod = "setZoomRatio"
    const val toggleFlashMethod = "toggleFlash"
    const val startScanMethod = "startScan"
    const val cancelScanMethod = "cancelScan"
    const val setScanDelayMethod = "setScanDelay"
    const val setCropAreaMethod = "setCropArea"

    /** Method name used to deliver recognized barcode values to Dart. */
    const val scanResultMethod = "onScanResult"
}
