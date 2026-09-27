import Foundation

/// Cancellable barcode result endpoint whose mutation and delivery require the main thread.
final class ScanResultSubscription {
    /// Main-thread result callback, cleared when the subscription is cancelled.
    private var onResult: ((ScannerBarcode) -> Void)?

    init(_ onResult: @escaping (ScannerBarcode) -> Void) {
        self.onResult = onResult
    }

    /// Cancellation suppresses all subsequent deliveries, including queued results.
    func cancel() {
        onResult = nil
    }

    /// Delivers a barcode on main if the subscription is still active.
    func deliver(_ result: ScannerBarcode) {
        onResult?(result)
    }
}
