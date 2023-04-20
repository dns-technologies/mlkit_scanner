//
//  CameraUtil.swift
//  mlkit_scanner
//
//  Created by Yaroslav on 19.04.2023.
//

import Foundation
import AVFoundation

class CameraUtil {
    /// Returns all available cameras on device.
    func getAvailableCameras() -> [AVCaptureDevice] {
        var deviceTypes: [AVCaptureDevice.DeviceType] = [
            .builtInWideAngleCamera,
            .builtInTelephotoCamera,
            .builtInDualCamera,
        ]
        if #available(iOS 13.0, *) {
            deviceTypes.append(contentsOf: [
                .builtInUltraWideCamera,
                .builtInDualWideCamera,
                .builtInTripleCamera,
            ])
        }

        let discoverySession = AVCaptureDevice.DiscoverySession(deviceTypes: deviceTypes, mediaType: .video, position: .unspecified)
        return discoverySession.devices
    }
}
