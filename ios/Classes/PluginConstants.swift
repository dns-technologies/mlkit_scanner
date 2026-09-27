//
//  PluginConstants.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 02.03.2021.
//

import Foundation

/// Names and keys forming the scanner's platform-channel protocol.
class PluginConstants {
    /// Shared method channel name.
    static let channelName = "mlkit_channel"
    /// Argument key identifying a registered camera consumer.
    static let viewIdArgument = "viewId"
    /// Event key containing a recognized barcode.
    static let barcodeArgument = "barcode"
    /// Event key containing a scalar event value.
    static let valueArgument = "value"
    /// Argument key containing the scan cooldown.
    static let delayArgument = "delay"
    /// Argument key containing a recognition area.
    static let cropRectArgument = "cropRect"
    /// Argument key containing a recognition type.
    static let typeArgument = "type"
    /// Method name for absolute zoom changes.
    static let setZoomRatioMethod = "setZoomRatio"
    /// Method name for desired torch state changes.
    static let toggleFlashMethod = "toggleFlash"
    /// Method name for starting barcode recognition.
    static let startScanMethod = "startScan"
    /// Method name for cancelling barcode recognition.
    static let cancelScanMethod = "cancelScan"
    /// Method name for changing the recognition cooldown.
    static let setScanDelayMethod = "setScanDelay"
    /// Method name for updating the normalized recognition area.
    static let setCropAreaMethod = "setCropAreaMethod"
    /// Method name for requesting a physical camera stop.
    static let pauseCameraMethod = "pauseCameraMethod"
    /// Method name for applying capture settings and starting the camera.
    static let resumeCameraMethod = "resumeCameraMethod"
    /// Event name for recognized barcode values.
    static let scanResultMethod = "onScanResult"
    /// Event name for torch state changes.
    static let changeTorchStateMethod = "changeTorchStateMethod"
    /// Method name for getting available iOS cameras.
    static let getIosAvailableCamerasMethod = "getIosAvailableCameras"
}
