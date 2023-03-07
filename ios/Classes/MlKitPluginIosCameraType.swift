//
//  MlKitPluginIosCameraType.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 06.03.2023.
//

import Foundation
import AVFoundation

enum MlKitPluginIosCameraType: Int, Encodable, Sendable {
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
    var mlKitPluginIosCameraType: MlKitPluginIosCameraType {
        switch self {
        case .builtInWideAngleCamera: return .builtInWideAngleCamera
        case .builtInTelephotoCamera: return .builtInTelephotoCamera
        case .builtInDualCamera: return .builtInDualCamera
        default:
            if #available(iOS 13.0, *) {
                switch self {
                case .builtInUltraWideCamera: return .builtInUltraWideCamera
                case .builtInDualWideCamera: return .builtInDualWideCamera
                case .builtInTripleCamera: return .builtInTripleCamera
                case .builtInTrueDepthCamera: return .builtInTrueDepthCamera
                default:
                    if #available(iOS 15.4, *) {
                        switch self {
                        case .builtInLiDARDepthCamera: return .builtInLiDARDepthCamera
                        default:
                            return .unknown
                        }
                    } else {
                        return .unknown
                    }
                }
            } else {
                return .unknown
            }
        }
    }
}

