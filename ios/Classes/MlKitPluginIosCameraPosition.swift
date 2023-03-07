//
//  MlKitPluginCameraPosition.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 06.03.2023.
//

import Foundation
import AVFoundation

enum MlKitPluginIosCameraPosition: Int {
    case unspecified = 0
    case back = 1
    case front = 2
}

extension AVCaptureDevice.Position {
    static fileprivate let devicePositionToPluginCameraPosition: [AVCaptureDevice.Position: MlKitPluginIosCameraPosition] = [
        .back: .back,
        .front: .front,
    ]
    
    var mlKitPluginIosCameraPosition: MlKitPluginIosCameraPosition {
        AVCaptureDevice.Position.devicePositionToPluginCameraPosition[self] ?? .unspecified
    }
}

extension MlKitPluginIosCameraPosition {
    static fileprivate let pluginPositionToDevicePosition: [MlKitPluginIosCameraPosition: AVCaptureDevice.Position] = {
        var map: [MlKitPluginIosCameraPosition: AVCaptureDevice.Position] = [:]
        for (devicePositon, pluginCameraPosition) in AVCaptureDevice.Position.devicePositionToPluginCameraPosition {
            map[pluginCameraPosition] = devicePositon
        }
        return map
    }()
    
    var devicePosition: AVCaptureDevice.Position? {
        MlKitPluginIosCameraPosition.pluginPositionToDevicePosition[self]
    }
}
