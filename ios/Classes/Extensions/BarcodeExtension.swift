//
//  BarcodeExtension.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 12.04.2023.
//

import Foundation
import MLKitBarcodeScanning

extension Barcode {
    func toJson() -> [String: Any?] {
        [
            "raw_value": rawValue!,
            "display_value": displayValue,
            "format": format.rawValue,
            "value_type": valueType.rawValue,
        ]
    }
}
