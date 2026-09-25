import Foundation

/// Accepts the first frame available after the cooldown selected by the previous result.
final class FrameAnalysisGate {
    /// Protects mutable state shared across callback queues.
    private let lock = NSLock()
    /// Monotonic clock used to evaluate recognition cooldowns.
    private let currentTimeMilliseconds: () -> TimeInterval
    /// Cooldown after a successful scan; protected by lock.
    private var successfulScanPeriodMilliseconds: Int
    /// Earliest allowed start time; protected by lock.
    private var nextAnalysisTimeMilliseconds: TimeInterval = 0
    /// Whether frame analysis is unfinished; protected by lock.
    private var isAnalysisInProgress = false

    /// Creates a gate with a successful-result cooldown and monotonic clock.
    init(
        successfulScanPeriodMilliseconds: Int,
        currentTimeMilliseconds: @escaping () -> TimeInterval = {
            ProcessInfo.processInfo.systemUptime * 1_000
        }
    ) {
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
        lock.lock()
        successfulScanPeriodMilliseconds = periodMilliseconds
        lock.unlock()
    }

    /// Fixed cooldown after an analysis with no barcode result.
    private static let failedAnalysisIntervalMilliseconds = 1_000
}
