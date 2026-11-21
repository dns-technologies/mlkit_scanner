import Foundation

/// Serializes analysis and applies cooldowns within the current recognition subscription.
final class FrameAnalysisGate {
    private enum AnalysisState {
        /// No analysis is running.
        case idle
        /// Analysis whose outcome determines the next cooldown.
        case current
        /// Analysis that must finish before newer work can start.
        case obsolete
    }

    /// Fixed cooldown after an analysis with no barcode result.
    private static let failedAnalysisIntervalMilliseconds = 1_000

    /// Protects mutable state shared across callback queues.
    private let lock = NSLock()

    /// Monotonic clock used to evaluate recognition cooldowns.
    private let currentTimeMilliseconds: () -> TimeInterval

    /// Cooldown after a successful scan; protected by lock.
    private var successfulScanPeriodMilliseconds: Int

    /// Earliest allowed start time; protected by lock.
    private var nextAnalysisTimeMilliseconds: TimeInterval = 0

    /// Current analysis and its eligibility to set the deadline; protected by lock.
    private var analysisState: AnalysisState = .idle

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
        guard analysisState == .idle,
              currentTimeMilliseconds() >= nextAnalysisTimeMilliseconds else {
            return false
        }
        analysisState = .current
        return true
    }

    /// Ends the analysis and applies its cooldown if it still belongs to the current subscription.
    func completeAnalysis(barcodeFound: Bool) {
        lock.lock()
        defer { lock.unlock() }
        if analysisState == .current {
            let cooldown = barcodeFound
                ? successfulScanPeriodMilliseconds
                : Self.failedAnalysisIntervalMilliseconds
            nextAnalysisTimeMilliseconds = currentTimeMilliseconds() + TimeInterval(cooldown)
        }
        analysisState = .idle
    }

    /// Makes the next subscription eligible as soon as any unfinished analysis completes.
    func resetCooldown() {
        lock.lock()
        nextAnalysisTimeMilliseconds = 0
        if analysisState == .current { analysisState = .obsolete }
        lock.unlock()
    }

    /// Updates the cooldown applied after future successful recognitions.
    func updateSuccessfulScanPeriod(_ periodMilliseconds: Int) {
        lock.lock()
        successfulScanPeriodMilliseconds = periodMilliseconds
        lock.unlock()
    }
}
