//
//  CropRect.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 12.03.2021.
//

import Foundation

/// Model for setting detection area of recognizers.
/// 
/// If detection area is bigger than `AVCaptureVideoOutput` size, there won't any detection.
struct CropRect {
    /// Width relative to width of the `AVCaptureVideoOutput` in percentage.
    ///  
    /// For example: 0.5 -  widgth of detection area equals to half of the `AVCaptureVideoOutput` width.
    let scaleWidth: CGFloat
    /// Height relative to height of the `AVCaptureVideoOutput` in percentage.
    ///
    /// For example: 1 -  height of detection area equals to the `AVCaptureVideoOutput` height.
    let scaleHeight: CGFloat
    /// X-axis offset in percentage from centerX of  `AVCaptureVideoOutput` size rect.
    ///
    /// For example: Coordinate of the centerX is 3. Whole lenght is 6. Coordinates of the crop area centerX
    /// if 4.5. Offset equals: (4.5 - 3) / 3 = 0.5. Offset forward by 50 %.
    /// If `offsetX == 0` then centerX of the  `AVCaptureVideoOutput` equals centerX of the  `AVCaptureVideoOutput` size rect.
    let offsetX: CGFloat
    /// Y-axis offset in percentage from center Y of `AVCaptureVideoOutput` size rect.
    ///
    /// For example: Coordinate of the centerY is 3. Whole lenght is 6. Coordinates of the crop area centerY
    /// if 1.5. Offset equals: (1.5 - 3) / 3 = -0.5. Offset back by 50 %.
    /// If `offsetY == 0` then centerY of the [CropRect] equals centerY of the `AVCaptureVideoOutput` size rect.
    let offsetY: CGFloat
    
    /// Initialize `CropRect` from Flutter Platform Channel side arguments.
    /// Returns nil if has error in arguments.
    init(arguments: [String: Any]) throws {
        func finiteValue(_ key: String) throws -> CGFloat {
            guard
                !(arguments[key] is Bool),
                let number = arguments[key] as? NSNumber,
                number.doubleValue.isFinite
            else {
                throw MlKitPluginError.invalidArguments
            }
            return CGFloat(number.doubleValue)
        }

        let scaleWidth = try finiteValue("scaleWidth")
        let scaleHeight = try finiteValue("scaleHeight")
        guard scaleWidth > 0, scaleHeight > 0 else {
            throw MlKitPluginError.invalidArguments
        }
        self.scaleWidth = scaleWidth
        self.scaleHeight = scaleHeight
        self.offsetX = try finiteValue("offsetX")
        self.offsetY = try finiteValue("offsetY")
    }
}
