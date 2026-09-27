//
//  CameraUtil.swift
//  mlkit_scanner
//
//  Created by Yaroslav on 19.04.2023.
//

import Foundation
import AVFoundation

/// Discovers capture devices that meet the supported camera criteria.
class CameraUtil {

    /// Discovers supported video cameras, including both front and rear positions.
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
