//
//  MlKitPluginCameraPosition.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 06.03.2023.
//

import Foundation
import AVFoundation

enum MlKitPluginIosCameraPosition: Int, Encodable {
    case unspecified = 0
    case back = 1
    case front = 2
}

extension AVCaptureDevice.Position {
    var mlKitPluginIosCameraPosition: MlKitPluginIosCameraPosition {
        switch self {
        case .back: return .back
        case .front: return .front
        default: return .unspecified
        }
    }
}
