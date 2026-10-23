import Foundation
import XCTest
@testable import mlkit_scanner

final class FrameAnalysisGateTests: XCTestCase {
    func testFirstFrameIsAcceptedImmediatelyAndConcurrentFramesAreRejected() {
        let now: TimeInterval = 10
        let gate = FrameAnalysisGate(
            successfulScanPeriodMilliseconds: 250,
            currentTimeMilliseconds: { now }
        )

        XCTAssertTrue(gate.beginAnalysis())
        XCTAssertFalse(gate.beginAnalysis())
    }

    func testFailedAnalysisUsesOneSecondRetryCooldown() {
        var now: TimeInterval = 100
        let gate = FrameAnalysisGate(
            successfulScanPeriodMilliseconds: 5_000,
            currentTimeMilliseconds: { now }
        )

        XCTAssertTrue(gate.beginAnalysis())
        now = 500
        gate.completeAnalysis(barcodeFound: false)
        now = 1_499
        XCTAssertFalse(gate.beginAnalysis())
        now = 1_500
        XCTAssertTrue(gate.beginAnalysis())
    }

    func testSuccessfulAnalysisUsesExactConfiguredCooldown() {
        var now: TimeInterval = 1_000
        let gate = FrameAnalysisGate(
            successfulScanPeriodMilliseconds: 275,
            currentTimeMilliseconds: { now }
        )

        XCTAssertTrue(gate.beginAnalysis())
        now = 2_000
        gate.completeAnalysis(barcodeFound: true)
        now = 2_274
        XCTAssertFalse(gate.beginAnalysis())
        now = 2_275
        XCTAssertTrue(gate.beginAnalysis())
    }

    func testPeriodUpdatedDuringAnalysisIsUsedAtSuccessfulCompletion() {
        var now: TimeInterval = 500
        let gate = FrameAnalysisGate(
            successfulScanPeriodMilliseconds: 100,
            currentTimeMilliseconds: { now }
        )

        XCTAssertTrue(gate.beginAnalysis())
        gate.updateSuccessfulScanPeriod(400)
        gate.completeAnalysis(barcodeFound: true)
        now = 899
        XCTAssertFalse(gate.beginAnalysis())
        now = 900
        XCTAssertTrue(gate.beginAnalysis())
    }

    func testZeroSuccessfulCooldownAcceptsNextAvailableFrame() {
        let now: TimeInterval = 42
        let gate = FrameAnalysisGate(
            successfulScanPeriodMilliseconds: 0,
            currentTimeMilliseconds: { now }
        )

        XCTAssertTrue(gate.beginAnalysis())
        gate.completeAnalysis(barcodeFound: true)
        XCTAssertTrue(gate.beginAnalysis())
    }

    func testPeriodUpdateDoesNotChangeAnActiveCooldown() {
        var now: TimeInterval = 0
        let gate = FrameAnalysisGate(
            successfulScanPeriodMilliseconds: 100,
            currentTimeMilliseconds: { now }
        )
        XCTAssertTrue(gate.beginAnalysis())
        gate.completeAnalysis(barcodeFound: true)

        gate.updateSuccessfulScanPeriod(0)

        now = 99
        XCTAssertFalse(gate.beginAnalysis())
        now = 100
        XCTAssertTrue(gate.beginAnalysis())
        gate.completeAnalysis(barcodeFound: true)
        XCTAssertTrue(gate.beginAnalysis())
    }

    func testSkippedFramesDoNotPostponeTheRetryDeadline() {
        var now: TimeInterval = 0
        let gate = FrameAnalysisGate(
            successfulScanPeriodMilliseconds: 0,
            currentTimeMilliseconds: { now }
        )
        XCTAssertTrue(gate.beginAnalysis())
        gate.completeAnalysis(barcodeFound: false)

        for instant in [1, 100, 500, 999] {
            now = TimeInterval(instant)
            XCTAssertFalse(gate.beginAnalysis())
        }

        now = 1_000
        XCTAssertTrue(gate.beginAnalysis())
    }

    func testLargePeriodDoesNotOverflowTheChannelIntegerRange() {
        var now: TimeInterval = 200
        let gate = FrameAnalysisGate(
            successfulScanPeriodMilliseconds: Int(Int32.max),
            currentTimeMilliseconds: { now }
        )
        XCTAssertTrue(gate.beginAnalysis())
        gate.completeAnalysis(barcodeFound: true)

        now = 200 + TimeInterval(Int32.max) - 1
        XCTAssertFalse(gate.beginAnalysis())
        now += 1
        XCTAssertTrue(gate.beginAnalysis())
    }

    func testOnlyOneConcurrentCallerCanBeginAnalysis() {
        let gate = FrameAnalysisGate(successfulScanPeriodMilliseconds: 0)
        let resultLock = NSLock()
        var acceptedCount = 0

        DispatchQueue.concurrentPerform(iterations: 64) { _ in
            guard gate.beginAnalysis() else { return }
            resultLock.lock()
            acceptedCount += 1
            resultLock.unlock()
        }

        XCTAssertEqual(acceptedCount, 1)
    }
}
