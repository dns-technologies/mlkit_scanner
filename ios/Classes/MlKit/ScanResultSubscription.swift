import Foundation

/// Main-thread result endpoint. Analysis may retain it, but cannot mutate or invoke it off main.
final class ScanResultSubscription {
    /// Main-thread result callback, cleared when the subscription is cancelled.
    private var onResult: ((ScannerBarcode) -> Void)?

    /// Creates a cancellable main-thread barcode endpoint.
    init(_ onResult: @escaping (ScannerBarcode) -> Void) {
        self.onResult = onResult
    }

    /// Clear before any subsequent delivery, including A → B → A with the same analyzer.
    func cancel() {
        onResult = nil
    }

    /// Delivers a barcode on main if the subscription is still active.
    func deliver(_ result: ScannerBarcode) {
        onResult?(result)
    }
}
