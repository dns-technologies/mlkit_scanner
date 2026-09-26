/// Configurable barcode recognition with subscription-scoped frame delivery.
protocol BarcodeAnalyzing: AnyObject {
    /// Updates the cooldown after successful recognition.
    func setDelay(delay: Int)

    /// Updates the normalized area analyzed in future frames.
    func updateCropRect(cropRect: CropRect)

    /// Creates a result subscription and revokes the previous one.
    func subscribe(_ onResult: @escaping (ScannerBarcode) -> Void) -> ScanResultSubscription

    /// Creates a frame receiver bound to the supplied result subscription.
    func input(for listener: ScanResultSubscription) -> RecognitionHandler

    /// Revokes current result delivery, including already queued results.
    func unsubscribe()
}
