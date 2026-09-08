import Foundation
import MLKitBarcodeScanning

/// Main-thread result endpoint. Analysis may retain it, but cannot mutate or invoke it off main.
final class ScanResultSubscription {
    private var onResult: ((Barcode) -> Void)?

    init(_ onResult: @escaping (Barcode) -> Void) { self.onResult = onResult }

    /// Clear before any subsequent delivery, including A → B → A with the same analyzer.
    func cancel() { onResult = nil }

    func deliver(_ result: Barcode) { onResult?(result) }
}
