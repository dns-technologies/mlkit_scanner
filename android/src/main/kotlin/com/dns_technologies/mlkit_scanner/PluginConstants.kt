package com.dns_technologies.mlkit_scanner

/** Contains method channel names used by the Dart and Android plugin sides. */
internal object PluginConstants {
    /** Shared tag used by Android scanner components. */
    const val LOG_TAG = "MLKIT_SCANNER_PLUGIN"

    /** Argument key identifying the logical widget that owns a command or event. */
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

    /** Command applying an absolute camera zoom ratio. */
    const val setZoomRatioMethod = "setZoomRatio"

    /** Command applying the desired torch state. */
    const val toggleFlashMethod = "toggleFlash"

    /** Command enabling recognition for the prepared result endpoint. */
    const val startScanMethod = "startScan"

    /** Command stopping recognition and closing its result endpoint. */
    const val cancelScanMethod = "cancelScan"

    /** Command updating the cooldown after successful recognition. */
    const val setScanDelayMethod = "setScanDelay"

    /** Command updating recognition geometry within the preview. */
    const val setCropAreaMethod = "setCropAreaMethod"

    /** Stops native work; Dart decides whether to retain or release controller ownership. */
    const val pauseCameraMethod = "pauseCameraMethod"

    /** Selects a preview and applies Dart settings on startup, resume or ownership transfer. */
    const val resumeCameraMethod = "resumeCameraMethod"

    /** Method name used to deliver recognized barcode values to Dart. */
    const val scanResultMethod = "onScanResult"
}
