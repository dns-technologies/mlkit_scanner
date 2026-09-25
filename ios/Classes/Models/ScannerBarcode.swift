/// Recognized barcode data independent of the recognition backend.
struct ScannerBarcode {
    /// Decoded barcode contents.
    let rawValue: String
    /// Optional human-readable representation.
    let displayValue: String?
    /// Barcode format code used by the scanner channel.
    let format: Int
    /// Content type code used by the scanner channel.
    let valueType: Int

    /// Encodes the scanner's existing cross-platform result contract.
    func toJson() -> [String: Any?] {
        ["raw_value": rawValue, "display_value": displayValue,
         "format": format, "value_type": valueType]
    }
}
