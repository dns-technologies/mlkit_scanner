//
//  PluginConstants.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 02.03.2021.
//

import Foundation

/// Constants of the Plugin.
class PluginConstants {
    /// Shared method channel name.
    static let channelName = "mlkit_channel"
    /// Argument key identifying a logical Flutter widget.
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
    /// Stops native work; Dart decides whether to retain or release controller ownership.
    static let pauseCameraMethod = "pauseCameraMethod"
    /// Selects a preview and applies Dart settings on startup, resume or ownership transfer.
    static let resumeCameraMethod = "resumeCameraMethod"
    /// Method name for invoking Flutter Side method with success recognitions.
    static let scanResultMethod = "onScanResult"
    /// Method name for inform flutter side when torch change state
    static let changeTorchStateMethod = "changeTorchStateMethod"
    /// Method name for getting available iOS cameras.
    static let getIosAvailableCamerasMethod = "getIosAvailableCameras"
}
