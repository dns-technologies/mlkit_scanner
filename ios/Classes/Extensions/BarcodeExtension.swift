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
            "rawValue": rawValue!,
            "displayValue": displayValue,
        ]
    }
}
