//
//  BarcodeExtension.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 12.04.2023.
//

import Foundation
import MLKitBarcodeScanning

extension Barcode {
    /// Creates json for transmission over the platform channel.
    func toJson() -> [String: Any?] {
        let mappedFormatRawValue = (format == .all) ? 0 : format.rawValue
        
        return [
            "raw_value": rawValue!,
            "display_value": displayValue,
            "format": mappedFormatRawValue,
            "value_type": valueType.rawValue,
        ]
    }
}
