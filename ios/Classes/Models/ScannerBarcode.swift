/// Recognized barcode contents and classification codes.
struct ScannerBarcode {
    /// Decoded barcode contents.
    let rawValue: String
    /// Optional human-readable representation.
    let displayValue: String?
    /// Stable barcode format code.
    let format: Int
    /// Stable barcode content type code.
    let valueType: Int

    /// Serializes barcode values into a string-keyed dictionary.
    func toJson() -> [String: Any?] {
        ["raw_value": rawValue, "display_value": displayValue,
         "format": format, "value_type": valueType]
    }
}
