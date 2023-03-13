//
//  MlKitPluginIosCameraType.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 06.03.2023.
//

import Foundation
import AVFoundation

enum MlKitPluginIosCameraType: Int, Codable {
    case unknown = 0
    case builtInWideAngleCamera = 1
    case builtInTelephotoCamera = 2
    case builtInDualCamera = 3
    case builtInUltraWideCamera = 4
    case builtInDualWideCamera = 5
    case builtInTripleCamera = 6
    case builtInTrueDepthCamera = 7
    case builtInLiDARDepthCamera = 8
}

extension AVCaptureDevice.DeviceType {
    static fileprivate let deviceTypeToCameraType: [AVCaptureDevice.DeviceType: MlKitPluginIosCameraType] = {
        var map: [AVCaptureDevice.DeviceType: MlKitPluginIosCameraType] = [
             .builtInWideAngleCamera: .builtInWideAngleCamera,
             .builtInTelephotoCamera: .builtInTelephotoCamera,
             .builtInDualCamera: .builtInDualCamera,
        ]
        if #available(iOS 13.0, *) {
            map[.builtInUltraWideCamera] = .builtInUltraWideCamera
            map[.builtInDualWideCamera] = .builtInDualWideCamera
            map[.builtInTripleCamera] = .builtInTripleCamera
            map[.builtInTrueDepthCamera] = .builtInTrueDepthCamera
        }
        if #available(iOS 15.4, *) {
            map[.builtInLiDARDepthCamera] = .builtInLiDARDepthCamera
        }
        return map
    }()
    
    var mlKitPluginIosCameraType: MlKitPluginIosCameraType {
        AVCaptureDevice.DeviceType.deviceTypeToCameraType[self] ?? .unknown
    }
}

extension MlKitPluginIosCameraType {
    static fileprivate let cameraTypeToDeviceType: [MlKitPluginIosCameraType: AVCaptureDevice.DeviceType] = {
        var map: [MlKitPluginIosCameraType: AVCaptureDevice.DeviceType] = [:]
        for (deviceType, cameraType) in AVCaptureDevice.DeviceType.deviceTypeToCameraType {
            map[cameraType] = deviceType
        }
        return map
    }()
    
    var deviceType: AVCaptureDevice.DeviceType? {
        MlKitPluginIosCameraType.cameraTypeToDeviceType[self]
    }
}
