//
//  CropRect.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 12.03.2021.
//

import Foundation

/// Normalized barcode recognition rectangle relative to the camera preview.
///
/// The portion inside the preview is analyzed. A rectangle completely outside
/// the preview produces no recognition results.
struct CropRect {
    /// Rectangle width as a fraction of the preview width.
    ///
    /// For example, `0.5` makes the recognition area half as wide as the preview.
    let scaleWidth: CGFloat
    /// Rectangle height as a fraction of the preview height.
    ///
    /// For example, `1` makes the recognition area as tall as the preview.
    let scaleHeight: CGFloat
    /// Horizontal center offset normalized to half the preview width.
    ///
    /// `0` centers the rectangle; `1` and `-1` move its center to the right and
    /// left preview edges respectively.
    let offsetX: CGFloat
    /// Vertical center offset normalized to half the preview height.
    ///
    /// `0` centers the rectangle; `1` and `-1` move its center to the bottom and
    /// top preview edges respectively.
    let offsetY: CGFloat
    
    /// Creates validated crop geometry from Flutter platform-channel arguments.
    init(arguments: [String: Any]) throws {
        /// Reads one required finite numeric argument.
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
