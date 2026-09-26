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

    /// Creates a centered recognition area covering the full preview.
    init() {
        scaleWidth = 1
        scaleHeight = 1
        offsetX = 0
        offsetY = 0
    }

    /// Decodes crop geometry; Dart validates the positive scale requirement.
    init(arguments: [String: Any]) throws {

        /// Reads one finite numeric argument or its cross-platform default.
        func finiteValue(_ key: String, default defaultValue: CGFloat) throws -> CGFloat {
            guard let rawValue = arguments[key], !(rawValue is NSNull) else {
                return defaultValue
            }
            return CGFloat(try PlatformChannelScalar.finiteDouble(from: rawValue))
        }

        scaleWidth = try finiteValue("scaleWidth", default: 1)
        scaleHeight = try finiteValue("scaleHeight", default: 1)
        self.offsetX = try finiteValue("offsetX", default: 0)
        self.offsetY = try finiteValue("offsetY", default: 0)
    }
}
