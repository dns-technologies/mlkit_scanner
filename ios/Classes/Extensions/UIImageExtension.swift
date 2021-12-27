//
//  UIImageExtension.swift
//  GoogleDataTransport
//
//  Created by ООО "ДНС Технологии" on 10.03.2021.
//

import UIKit
import AVFoundation

extension UIImage {
    
    /// Initialization of `UIImage?` by `CIImage` from `AVCaptureVideoOutput` with cropping by scale:
    ///  `scaleX` scale by coordinate X
    ///  `scaleY` scale by coordinate Y
    ///  `orientation` orientation of `AVCaptureVideoOutput`. 
    ///  `cropRect` optional `CropRect?`, if not nil crops the image by this `CropRect`
    /// Return `nil` if it can't create `UIImage` with this scales and `CropRect` 
    convenience init?(ciImage: CIImage, scaleX: CGFloat, scaleY: CGFloat, orientation: AVCaptureVideoOrientation, cropRect: CropRect?) {
        var height: CGFloat
        var width: CGFloat
        switch orientation {
        case .landscapeLeft: fallthrough
        case .landscapeRight:
            height = ciImage.extent.height * scaleX
            width = ciImage.extent.width * scaleY
        default:
            height = ciImage.extent.height * scaleY
            width = ciImage.extent.width * scaleX
        }
        let x = ciImage.extent.midX - width / 2
        let y = ciImage.extent.midY - height / 2
        var rect = CGRect(x: x, y: y, width: width, height: height)
        if let crop = cropRect {
            rect = rect.cropBy(cropRect: crop, orientation: orientation, scaleX: scaleX, scaleY: scaleY)
        }
        let context = CIContext()
        guard let cgImage = context.createCGImage(ciImage, from: rect) else {
           return nil
        }
        self.init(cgImage: cgImage)
    }
    
    static func fromLibraryAssets(name: String) -> UIImage? {
        let bundlePath = Bundle(for: SwiftMlkitScannerPlugin.self)
            .path(forResource: "Assets", ofType: "bundle")
        guard let path = bundlePath else {
            return nil
        }
        let bundle = Bundle(path: path)
        return UIImage(named: name, in: bundle, compatibleWith: nil)
    }
}

