package com.dns_technologies.mlkit_scanner

/** Scanner method-channel names and argument keys. */
internal object PluginConstants {
    /** Tag for scanner diagnostic messages. */
    const val LOG_TAG = "MLKIT_SCANNER_PLUGIN"

    /** Argument key identifying the consumer associated with a command or event. */
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

    /** Command requesting a physical camera stop. */
    const val pauseCameraMethod = "pauseCameraMethod"

    /** Command applying capture settings and starting the camera. */
    const val resumeCameraMethod = "resumeCameraMethod"

    /** Event name for recognized barcode values. */
    const val scanResultMethod = "onScanResult"
}
