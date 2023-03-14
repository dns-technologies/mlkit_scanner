//
//  DeviceTypeCodeExtension.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 14.03.2023.
//

import Foundation
import AVFoundation

extension AVCaptureDevice.DeviceType {
    /// Code of unsuppored types.
    static let unsupportedCode = -1
    
    /// Code of type for transmission over the platform channel.
    var code: Int {
        AVCaptureDevice.DeviceType.typeToCode[self] ?? AVCaptureDevice.DeviceType.unsupportedCode
    }
    
    /// Returns type with corresponding `code`.
    static func fromCode(_ code: Int) -> AVCaptureDevice.DeviceType? {
        AVCaptureDevice.DeviceType.codeToType[code]
    }
    
    static private let typeToCode: [AVCaptureDevice.DeviceType: Int] = {
        var map: [AVCaptureDevice.DeviceType: Int] = [
             .builtInWideAngleCamera: 0,
             .builtInTelephotoCamera: 1,
             .builtInDualCamera: 2,
        ]
        if #available(iOS 13.0, *) {
            map[.builtInUltraWideCamera] = 3
            map[.builtInDualWideCamera] = 4
            map[.builtInTripleCamera] = 5
        }
        return map
    }()
    
    static private let codeToType: [Int: AVCaptureDevice.DeviceType] = {
        var map: [Int: AVCaptureDevice.DeviceType] = [:]
        for (type, code) in typeToCode {
            map[code] = type
        }
        return map
    }()
}
