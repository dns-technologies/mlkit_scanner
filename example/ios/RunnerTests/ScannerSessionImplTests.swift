import AVFoundation
import Foundation
import XCTest
@testable import mlkit_scanner

final class ScannerSessionImplTests: XCTestCase {
    func testFirstCodecPlatformViewCanCaptureAndStartScan() throws {
        let session = makeSession()
        let viewId = try ScannerMethodArguments.viewId([
            "viewId": NSNumber(value: Int32(0)),
        ])
        let options = try ScannerMethodArguments.scanOptions([
            "viewId": NSNumber(value: Int32(0)),
            "type": NSNumber(value: Int32(0)),
            "delay": NSNumber(value: Int32(100)),
        ])
        let view = FakeCameraPreview(viewId: viewId)
        session.attachView(viewId: viewId, view: view)
        var captureCompletionCount = 0

        session.captureCamera(viewId: viewId) { error in
            XCTAssertNil(error)
            captureCompletionCount += 1
        }
        try session.startScan(
            viewId: options.viewId,
            type: options.type,
            delay: options.delay
        )

        XCTAssertEqual(viewId, 0)
        XCTAssertEqual(captureCompletionCount, 1)
        XCTAssertEqual(view.resumeCount, 1)
        XCTAssertNotNil(view.recognitionHandler)
        XCTAssertEqual(view.scanActiveValues.last, true)
    }

    func testInitialCropIsAppliedToViewDuringRegistration() throws {
        let session = makeSession()
        let view = FakeCameraPreview(viewId: 1)
        let crop = try makeCrop(scaleWidth: 0.5)

        session.attachView(
            viewId: 1,
            view: view,
            registration: registration(crop: crop)
        )

        XCTAssertEqual(view.cropAreas.count, 1)
        XCTAssertEqual(view.cropAreas.first?.scaleWidth, 0.5)
        XCTAssertEqual(view.resumeCount, 0)
    }

    func testCaptureWaitsForLayoutAndAppliesControlsBeforeWaitingForFirstFrame() throws {
        let session = makeSession()
        let view = FakeCameraPreview(viewId: 1)
        view.isLayoutReady = false
        view.completesResumeAutomatically = false
        let crop = try makeCrop(scaleWidth: 0.5)
        session.attachView(
            viewId: 1,
            view: view,
            registration: registration(zoom: 2, flash: true, crop: crop)
        )
        var results: [Error?] = []

        session.captureCamera(viewId: 1) { results.append($0) }

        XCTAssertEqual(view.initializeCount, 1)
        XCTAssertEqual(view.resumeCount, 0)
        XCTAssertTrue(view.zoomRatios.isEmpty)
        XCTAssertTrue(view.flashValues.isEmpty)
        XCTAssertTrue(results.isEmpty)

        view.markLayoutReady()

        XCTAssertEqual(view.resumeCount, 1)
        XCTAssertEqual(view.zoomRatios, [2])
        XCTAssertEqual(view.flashValues, [true])
        XCTAssertTrue(results.isEmpty)

        view.completeNextResume()

        XCTAssertEqual(results.count, 1)
        XCTAssertNil(results.first!)
    }

    func testRepeatedCaptureSharesInitializationAndFirstFrame() {
        let session = makeSession()
        let view = FakeCameraPreview(viewId: 1)
        view.completesInitializationAutomatically = false
        view.completesResumeAutomatically = false
        session.attachView(viewId: 1, view: view)
        var completionCount = 0

        session.captureCamera(viewId: 1) { _ in completionCount += 1 }
        session.captureCamera(viewId: 1) { _ in completionCount += 1 }

        XCTAssertEqual(view.initializeCount, 1)
        XCTAssertEqual(view.resumeCount, 0)
        XCTAssertEqual(completionCount, 0)

        view.completeNextInitialization()
        XCTAssertEqual(view.resumeCount, 1)
        XCTAssertEqual(completionCount, 0)

        view.completeNextResume()
        XCTAssertEqual(completionCount, 2)
    }

    func testDisposingPendingStartupLetsAnotherViewCaptureAndIgnoresLateCallback() {
        let session = makeSession()
        let first = FakeCameraPreview(viewId: 1)
        first.completesInitializationAutomatically = false
        let second = FakeCameraPreview(viewId: 2)
        session.attachView(viewId: 1, view: first)
        session.attachView(viewId: 2, view: second)
        var firstResult: Error?
        var secondResult: Error?

        session.captureCamera(viewId: 1) { firstResult = $0 }
        session.disposeView(viewId: 1, registrationToken: first.registrationToken)
        session.captureCamera(viewId: 2) { secondResult = $0 }

        XCTAssertNil(firstResult)
        XCTAssertNil(secondResult)
        XCTAssertEqual(second.resumeCount, 1)

        first.completeNextInitialization()

        XCTAssertEqual(second.pauseCount, 0)
        XCTAssertEqual(second.resumeCount, 1)
    }

    func testLateDisposalCannotRemoveReplacementWithReusedViewId() {
        let session = makeSession()
        let oldView = FakeCameraPreview(viewId: 1)
        session.attachView(viewId: 1, view: oldView)
        session.disposeView(viewId: 1, registrationToken: oldView.registrationToken)
        let replacement = FakeCameraPreview(viewId: 1)
        session.attachView(viewId: 1, view: replacement)

        session.disposeView(viewId: 1, registrationToken: oldView.registrationToken)
        var didComplete = false
        session.captureCamera(viewId: 1) { _ in didComplete = true }

        XCTAssertTrue(didComplete)
        XCTAssertEqual(replacement.resumeCount, 1)
        XCTAssertEqual(replacement.disposeCount, 0)
    }

    func testLateFirstFrameFromSupersededOwnerCannotChangeCurrentOwner() {
        let session = makeSession()
        let first = FakeCameraPreview(viewId: 1)
        first.completesResumeAutomatically = false
        let second = FakeCameraPreview(viewId: 2)
        session.attachView(viewId: 1, view: first, registration: registration(zoom: 2))
        session.attachView(viewId: 2, view: second, registration: registration(zoom: 3))
        var firstCompletionCount = 0

        session.captureCamera(viewId: 1) { _ in firstCompletionCount += 1 }
        session.captureCamera(viewId: 2) { _ in }

        XCTAssertEqual(firstCompletionCount, 1)
        XCTAssertEqual(first.zoomRatios, [2])
        XCTAssertEqual(second.zoomRatios, [3])

        first.completeNextResume()

        XCTAssertEqual(first.zoomRatios, [2])
        XCTAssertEqual(second.pauseCount, 0)
        XCTAssertEqual(second.zoomRatios, [3])
    }

    func testReleaseAndPauseFromPreviousOwnerDoNotStopCurrentOwner() {
        let session = makeSession()
        let first = FakeCameraPreview(viewId: 1)
        let second = FakeCameraPreview(viewId: 2)
        session.attachView(viewId: 1, view: first)
        session.attachView(viewId: 2, view: second)
        session.captureCamera(viewId: 1) { _ in }
        session.captureCamera(viewId: 2) { _ in }
        let secondPauseCount = second.pauseCount

        session.releaseCamera(viewId: 1) {}
        session.pauseCamera(viewId: 1) {}

        XCTAssertEqual(second.pauseCount, secondPauseCount)
        XCTAssertTrue(session.canApplyFocus(viewId: 2))
    }

    func testInactiveControlsAreRetainedWithoutTouchingCurrentCamera() throws {
        let session = makeSession()
        let first = FakeCameraPreview(viewId: 1)
        let second = FakeCameraPreview(viewId: 2)
        session.attachView(viewId: 1, view: first)
        session.attachView(viewId: 2, view: second)
        session.captureCamera(viewId: 1) { _ in }
        session.captureCamera(viewId: 2) { _ in }

        try session.setZoomRatio(viewId: 1, value: 2)
        try session.toggleFlash(viewId: 1)

        XCTAssertTrue(first.zoomRatios.isEmpty)
        XCTAssertTrue(first.flashValues.isEmpty)
        session.captureCamera(viewId: 1) { _ in }
        XCTAssertEqual(first.zoomRatios, [2])
        XCTAssertEqual(first.flashValues, [true])
    }

    func testRuntimeControlsKeepScanAndOverlayActive() throws {
        let session = makeSession()
        let view = FakeCameraPreview(viewId: 1)
        session.attachView(viewId: 1, view: view)
        session.captureCamera(viewId: 1) { _ in }
        try session.startScan(viewId: 1, type: .barcodeRecognition, delay: 100)
        view.scanActiveValues.removeAll()

        try session.setZoomRatio(viewId: 1, value: 2)
        try session.toggleFlash(viewId: 1)

        XCTAssertFalse(view.scanActiveValues.contains(false))
        XCTAssertNotNil(view.recognitionHandler)
    }

    func testHostLifecyclePausesAndRestoresOnlyCurrentOwner() {
        let center = NotificationCenter()
        let session = makeSession(notificationCenter: center)
        let view = FakeCameraPreview(viewId: 1)
        session.attachView(viewId: 1, view: view)
        session.captureCamera(viewId: 1) { _ in }

        center.post(name: UIApplication.willResignActiveNotification, object: nil)
        XCTAssertEqual(view.pauseCount, 1)
        XCTAssertFalse(session.canApplyFocus(viewId: 1))

        center.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        XCTAssertEqual(view.resumeCount, 2)
        XCTAssertTrue(session.canApplyFocus(viewId: 1))
    }

    func testScanIntentIsScopedToItsViewAndRestoredWhenThatViewReturns() throws {
        let session = makeSession()
        let first = FakeCameraPreview(viewId: 1)
        let second = FakeCameraPreview(viewId: 2)
        session.attachView(viewId: 1, view: first)
        session.attachView(viewId: 2, view: second)
        session.captureCamera(viewId: 1) { _ in }
        try session.startScan(viewId: 1, type: .barcodeRecognition, delay: 100)
        let firstHandler = first.recognitionHandler

        session.captureCamera(viewId: 2) { _ in }

        XCTAssertNil(first.recognitionHandler)
        XCTAssertNil(second.recognitionHandler)
        session.captureCamera(viewId: 1) { _ in }
        XCTAssertTrue(first.recognitionHandler === firstHandler)
        XCTAssertNil(second.recognitionHandler)
    }

    func testCancelScanWhileCoveredPreventsItFromReturning() throws {
        let session = makeSession()
        let first = FakeCameraPreview(viewId: 1)
        let second = FakeCameraPreview(viewId: 2)
        session.attachView(viewId: 1, view: first)
        session.attachView(viewId: 2, view: second)
        session.captureCamera(viewId: 1) { _ in }
        try session.startScan(viewId: 1, type: .barcodeRecognition, delay: 100)
        session.captureCamera(viewId: 2) { _ in }

        session.cancelScan(viewId: 1)
        session.captureCamera(viewId: 1) { _ in }

        XCTAssertNil(first.recognitionHandler)
        XCTAssertEqual(first.scanActiveValues.last, false)
    }

    func testHostInitiallyPausedDefersFirstFrameUntilItBecomesActive() {
        let center = NotificationCenter()
        let session = ScannerSessionImpl(
            onScanResult: { _, _ in },
            onTorchChanged: { _, _ in },
            notificationCenter: center,
            hostIsActive: { false }
        )
        let view = FakeCameraPreview(viewId: 1)
        session.attachView(viewId: 1, view: view)
        var completionCount = 0

        session.captureCamera(viewId: 1) { _ in completionCount += 1 }

        XCTAssertEqual(view.initializeCount, 1)
        XCTAssertEqual(view.resumeCount, 0)
        XCTAssertEqual(completionCount, 0)
        center.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        XCTAssertEqual(view.resumeCount, 1)
        XCTAssertEqual(completionCount, 1)
    }

    func testInitializationFailureIsPropagatedWithoutStartingCapture() {
        let session = makeSession()
        let view = FakeCameraPreview(viewId: 1)
        view.completesInitializationAutomatically = false
        session.attachView(viewId: 1, view: view)
        var result: Error?

        session.captureCamera(viewId: 1) { result = $0 }
        view.completeNextInitialization(error: TestError.cameraUnavailable)

        XCTAssertNotNil(result)
        XCTAssertEqual(view.resumeCount, 0)
        XCTAssertFalse(session.canApplyFocus(viewId: 1))
    }

    func testReleaseFailsPendingInitializationAndDisposesViewOnce() {
        let session = makeSession()
        let view = FakeCameraPreview(viewId: 1)
        view.completesInitializationAutomatically = false
        session.attachView(viewId: 1, view: view)
        var result: Error?

        session.captureCamera(viewId: 1) { result = $0 }
        session.release()

        XCTAssertEqual(result as? MlKitPluginError, .cameraSessionDisposed)
        XCTAssertEqual(view.disposeCount, 1)
        view.completeNextInitialization()
        XCTAssertEqual(view.resumeCount, 0)
        session.release()
        XCTAssertEqual(view.disposeCount, 1)
    }

    func testUnsupportedRetainedTorchFallsBackToOffAndStillActivates() {
        var torchEvents: [(Int64, Bool)] = []
        let session = ScannerSessionImpl(
            onScanResult: { _, _ in },
            onTorchChanged: { torchEvents.append(($0, $1)) },
            notificationCenter: NotificationCenter(),
            hostIsActive: { true }
        )
        let view = FakeCameraPreview(viewId: 5)
        view.unsupportedEnabledFlash = true
        session.attachView(
            viewId: 5,
            view: view,
            registration: registration(flash: true)
        )
        var result: Error?

        session.captureCamera(viewId: 5) { result = $0 }

        XCTAssertNil(result)
        XCTAssertEqual(view.flashValues, [true, false])
        XCTAssertEqual(view.resumeCount, 1)
        XCTAssertEqual(torchEvents.count, 1)
        XCTAssertEqual(torchEvents.first?.0, 5)
        XCTAssertEqual(torchEvents.first?.1, false)
    }

    func testDisposedViewCommandsAreSuccessfulNoOps() throws {
        let session = makeSession()
        let view = FakeCameraPreview(viewId: 1)
        session.attachView(viewId: 1, view: view)
        session.disposeView(viewId: 1, registrationToken: view.registrationToken)
        var captureResult: Error?

        session.captureCamera(viewId: 1) { captureResult = $0 }
        try session.setZoomRatio(viewId: 1, value: 2)
        try session.toggleFlash(viewId: 1)
        try session.startScan(viewId: 1, type: .barcodeRecognition, delay: 100)

        XCTAssertNil(captureResult)
        XCTAssertEqual(view.resumeCount, 0)
        XCTAssertTrue(view.zoomRatios.isEmpty)
        XCTAssertTrue(view.flashValues.isEmpty)
    }

    func testRuntimeControlErrorContainsOperationAndViewContext() throws {
        let session = makeSession()
        let view = FakeCameraPreview(viewId: 7)
        session.attachView(viewId: 7, view: view)
        session.captureCamera(viewId: 7) { _ in }
        view.zoomError = TestError.cameraUnavailable

        XCTAssertThrowsError(try session.setZoomRatio(viewId: 7, value: 2)) { error in
            guard let controlError = error as? CameraControlError else {
                return XCTFail("Expected CameraControlError, got \(error)")
            }
            XCTAssertEqual(controlError.operation, .zoom)
            XCTAssertEqual(controlError.viewId, 7)
            XCTAssertEqual(controlError.channelDetails["operation"] as? String, "zoom")
            XCTAssertEqual(controlError.channelDetails["viewId"] as? Int64, 7)
        }
    }

    func testFirstFrameFailureIsReportedAsAwaitOpenControlError() {
        let session = makeSession()
        let view = FakeCameraPreview(viewId: 9)
        view.completesResumeAutomatically = false
        session.attachView(viewId: 9, view: view)
        var result: Error?

        session.captureCamera(viewId: 9) { result = $0 }
        view.completeNextResume(error: TestError.cameraUnavailable)

        let controlError = result as? CameraControlError
        XCTAssertEqual(controlError?.operation, .awaitOpen)
        XCTAssertEqual(controlError?.viewId, 9)
    }

    private func makeSession(
        notificationCenter: NotificationCenter = NotificationCenter()
    ) -> ScannerSessionImpl {
        ScannerSessionImpl(
            onScanResult: { _, _ in },
            onTorchChanged: { _, _ in },
            notificationCenter: notificationCenter,
            hostIsActive: { true }
        )
    }

    private func registration(
        zoom: Double? = nil,
        flash: Bool? = nil,
        crop: CropRect? = nil
    ) -> ScannerViewRegistration {
        ScannerViewRegistration(
            size: nil,
            initialZoomRatio: zoom,
            initialFlashEnabled: flash,
            initialCropRect: crop,
            initialCamera: nil
        )
    }

    private func makeCrop(scaleWidth: Double) throws -> CropRect {
        try CropRect(arguments: [
            "scaleWidth": scaleWidth,
            "scaleHeight": 1.0,
            "offsetX": 0.0,
            "offsetY": 0.0,
        ])
    }
}

private enum TestError: Error {
    case cameraUnavailable
}

private final class FakeCameraPreview: CameraPreviewing {
    let viewId: Int64
    let registrationToken: UUID
    weak var recognitionHandler: RecognitionHandler?
    weak var cameraPreviewDelegate: CameraPreviewDelegate?
    var isLayoutReady = true
    var completesInitializationAutomatically = true
    var completesResumeAutomatically = true
    var completesPauseAutomatically = true
    var zoomError: Error?
    var flashError: Error?
    var unsupportedEnabledFlash = false
    private var layoutCompletions: [() -> Void] = []
    private var initializationCompletions: [(Error?) -> Void] = []
    private var resumeCompletions: [(Error?) -> Void] = []
    private var pauseCompletions: [() -> Void] = []
    private(set) var initializeCount = 0
    private(set) var resumeCount = 0
    private(set) var pauseCount = 0
    private(set) var disposeCount = 0
    private(set) var zoomRatios: [Double] = []
    private(set) var flashValues: [Bool] = []
    private(set) var cropAreas: [CropRect] = []
    var scanActiveValues: [Bool] = []

    init(viewId: Int64, registrationToken: UUID = UUID()) {
        self.viewId = viewId
        self.registrationToken = registrationToken
    }

    func whenLayoutReady(_ completion: @escaping () -> Void) {
        if isLayoutReady {
            completion()
        } else {
            layoutCompletions.append(completion)
        }
    }

    func markLayoutReady() {
        isLayoutReady = true
        let completions = layoutCompletions
        layoutCompletions.removeAll()
        completions.forEach { $0() }
    }

    func initCamera(completion: @escaping (Error?) -> Void) {
        initializeCount += 1
        if completesInitializationAutomatically {
            completion(nil)
        } else {
            initializationCompletions.append(completion)
        }
    }

    func completeNextInitialization(error: Error? = nil) {
        initializationCompletions.removeFirst()(error)
    }

    func setCamera(_ cameraData: CameraData) throws {}

    func setFlash(_ enabled: Bool) throws {
        flashValues.append(enabled)
        if enabled, unsupportedEnabledFlash {
            throw MlKitPluginError.deviceHasNotFlash
        }
        if let flashError = flashError { throw flashError }
    }

    func resetFocus() {}

    func pauseCamera(completion: @escaping () -> Void) {
        pauseCount += 1
        if completesPauseAutomatically {
            completion()
        } else {
            pauseCompletions.append(completion)
        }
    }

    func completeNextPause() {
        pauseCompletions.removeFirst()()
    }

    func resumeCamera(completion: @escaping (Error?) -> Void) {
        resumeCount += 1
        if completesResumeAutomatically {
            completion(nil)
        } else {
            resumeCompletions.append(completion)
        }
    }

    func completeNextResume(error: Error? = nil) {
        resumeCompletions.removeFirst()(error)
    }

    func setZoomRatio(_ value: Double) throws {
        zoomRatios.append(value)
        if let zoomError = zoomError { throw zoomError }
    }

    func setCropArea(_ cropRect: CropRect) {
        cropAreas.append(cropRect)
    }

    func setScanActive(_ isActive: Bool) {
        scanActiveValues.append(isActive)
    }

    func dispose() {
        disposeCount += 1
    }
}
