//
//  DevicePositionCodeExtension.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 14.03.2023.
//

import Foundation

import Foundation
import AVFoundation

extension AVCaptureDevice.Position {
    /// Code of unsuppored types.
    static let unsupportedCode = -1
    
    /// Code of position for transmission over the platform channel.
    var code: Int {
        AVCaptureDevice.Position.positionToCode[self] ?? AVCaptureDevice.Position.unsupportedCode
    }
    
    /// Returns the position corresponding to the `code`.
    static func fromCode(_ code: Int) -> AVCaptureDevice.Position? {
        AVCaptureDevice.Position.codeToPosition[code]
    }
    
    static private let positionToCode: [AVCaptureDevice.Position: Int] = [
        .unspecified: 0,
        .back: 1,
        .front: 2,
    ]
    
    static private let codeToPosition: [Int: AVCaptureDevice.Position] = {
        var map: [Int: AVCaptureDevice.Position] = [:]
        for (position, code) in positionToCode {
            map[code] = position
        }
        return map
    }()
}
