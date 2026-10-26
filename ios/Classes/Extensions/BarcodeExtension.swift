//
//  BarcodeExtension.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 12.04.2023.
//

import Foundation
import MLKitBarcodeScanning

extension Barcode {

    /// Maps an accepted recognition result into backend-independent scanner data.
    var scannerBarcode: ScannerBarcode {
        ScannerBarcode(rawValue: rawValue!, displayValue: displayValue,
            format: format == .all ? 0 : format.rawValue, valueType: valueType.rawValue)
    }

    /// Creates json for transmission over the platform channel.
    func toJson() -> [String: Any?] {
        scannerBarcode.toJson()
    }
}
