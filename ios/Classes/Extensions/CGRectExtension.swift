//
//  CGRectExtension.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 12.03.2021.
//

import Foundation
import AVFoundation

extension CGRect {
    
    /// Crop `CGRect` by `CropRect`, return new cropped `CGRect`.
    func cropBy(cropRect: CropRect, orientation: AVCaptureVideoOrientation, scaleX: CGFloat, scaleY: CGFloat) -> CGRect {
        switch orientation {
        case .landscapeLeft: fallthrough
        case .landscapeRight:
            let width = cropRect.scaleHeight * self.width * 1.2
            let height = cropRect.scaleWidth * self.height
            let x = midX + (cropRect.offsetY * midX * scaleY) - width / 2
            let y = midY + (cropRect.offsetX * midY * scaleX) - height / 2
            return CGRect(x: x, y: y, width: width, height: height)
        default:
            let width = cropRect.scaleWidth * self.width
            let height = cropRect.scaleHeight * self.height * 1.2
            let x = midX + (cropRect.offsetX * midX * scaleX) - width / 2
            let y = midY + (cropRect.offsetY * midY * scaleY) - height / 2
            return CGRect(x: x, y: y, width: width, height: height)
        }
    }
}
