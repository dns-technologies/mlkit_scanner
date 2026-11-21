//
//  CropRect.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 12.03.2021.
//

import Foundation

/// Normalized barcode recognition rectangle relative to the camera preview.
///
/// Scales describe size relative to the preview; offsets describe displacement
/// of the rectangle's center from the preview center.
/// Defaults to the centered full preview. Argument decoding requires finite values
/// and uses the same defaults for omitted coordinates.
struct CropRect: Equatable {
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

    init() {
        scaleWidth = 1
        scaleHeight = 1
        offsetX = 0
        offsetY = 0
    }

    init(arguments: [String: Any]) throws {
        scaleWidth = CGFloat(try arguments.optionalFiniteDouble("scaleWidth") ?? 1)
        scaleHeight = CGFloat(try arguments.optionalFiniteDouble("scaleHeight") ?? 1)
        offsetX = CGFloat(try arguments.optionalFiniteDouble("offsetX") ?? 0)
        offsetY = CGFloat(try arguments.optionalFiniteDouble("offsetY") ?? 0)
    }
}
