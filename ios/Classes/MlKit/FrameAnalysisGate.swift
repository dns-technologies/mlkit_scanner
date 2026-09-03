import Foundation

/// Accepts the first frame available after the cooldown selected by the previous result.
final class FrameAnalysisGate {
    private let lock = NSLock()
    private let currentTimeMilliseconds: () -> TimeInterval
    private var successfulScanPeriodMilliseconds: Int
    private var nextAnalysisTimeMilliseconds: TimeInterval = 0
    private var isAnalysisInProgress = false

    /// Creates a gate with a successful-result cooldown and monotonic clock.
    init(
        successfulScanPeriodMilliseconds: Int,
        currentTimeMilliseconds: @escaping () -> TimeInterval = {
            ProcessInfo.processInfo.systemUptime * 1_000
        }
    ) {
        precondition(successfulScanPeriodMilliseconds >= 0)
        self.successfulScanPeriodMilliseconds = successfulScanPeriodMilliseconds
        self.currentTimeMilliseconds = currentTimeMilliseconds
    }

    /// Atomically starts analysis when no other attempt or cooldown blocks it.
    func beginAnalysis() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !isAnalysisInProgress,
              currentTimeMilliseconds() >= nextAnalysisTimeMilliseconds else {
            return false
        }
        isAnalysisInProgress = true
        return true
    }

    /// Starts the next cooldown from completion using the recognition outcome.
    func completeAnalysis(barcodeFound: Bool) {
        lock.lock()
        defer { lock.unlock() }
        let cooldown = barcodeFound
            ? successfulScanPeriodMilliseconds
            : Self.failedAnalysisIntervalMilliseconds
        nextAnalysisTimeMilliseconds = currentTimeMilliseconds() + TimeInterval(cooldown)
        isAnalysisInProgress = false
    }

    /// Updates the cooldown applied after future successful recognitions.
    func updateSuccessfulScanPeriod(_ periodMilliseconds: Int) {
        precondition(periodMilliseconds >= 0)
        lock.lock()
        successfulScanPeriodMilliseconds = periodMilliseconds
        lock.unlock()
    }

    private static let failedAnalysisIntervalMilliseconds = 1_000
}
