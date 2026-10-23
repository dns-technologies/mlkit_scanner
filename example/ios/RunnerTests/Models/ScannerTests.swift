import UIKit
import CoreMedia
import XCTest
@testable import mlkit_scanner

final class ScannerTests: XCTestCase {
    private final class Fixture {
        let camera = TestCamera(); let notifications = NotificationCenter()
        let analyzer = TestAnalyzer()
        var analyzerAllocations = 0
        var resultOwners: [Int64] = []
        var previews: [CameraPreviewDescription?] = []
        var onPreviewChanged: ((CameraPreviewDescription?) -> Void)?
        var allocations = 0; var permissions: [(Bool) -> Void] = []
        lazy var runtime = mlkit_scanner.Scanner(onScanResult: { [unowned self] viewId, _ in self.resultOwners.append(viewId) }, onTorchChanged: { _, _ in },
            onPreviewChanged: { [unowned self] in self.previews.append($0); self.onPreviewChanged?($0) },
            notificationCenter: notifications, cameraFactory: { [unowned self] in self.allocations += 1; return self.camera },
            analyzerFactory: { [unowned self] in self.analyzerAllocations += 1; return self.analyzer },
            requestPermission: { [unowned self] in self.permissions.append($0) })
        func capture(_ id: Int64, zoom: Double = 1, geometry: CGSize = CGSize(width: 1, height: 1)) throws {
            runtime.captureCamera(viewId: id, configuration: try ScannerConfiguration(arguments: [
                "zoomRatio": zoom, "torchEnabled": false]), geometry: geometry) { XCTAssertNil($0) }
            permissions.removeFirst()(true)
        }
    }

    private final class TestAnalyzer: BarcodeAnalyzing, RecognitionHandler {
        var subscription: ScanResultSubscription?
        var delay = 0
        var cropUpdates = 0
        func setDelay(delay: Int) { self.delay = delay }
        func updateCropRect(cropRect: CropRect) { cropUpdates += 1 }
        func subscribe(_ onResult: @escaping (ScannerBarcode) -> Void) -> ScanResultSubscription {
            unsubscribe()
            let listener = ScanResultSubscription(onResult)
            subscription = listener
            return listener
        }
        func input(for listener: ScanResultSubscription) -> RecognitionHandler { self }
        func unsubscribe() { subscription?.cancel(); subscription = nil }
        func processVideoOutput(sampleBuffer: CMSampleBuffer, viewport: CGSize) {}
    }

    private final class TestCamera: CameraPreviewing {
        var isInitialized = false; var isTorchActive = false
        var recognitionHandler: RecognitionHandler?
        weak var cameraPreviewDelegate: CameraPreviewDelegate?
        var zooms: [Double] = []; var pauses = 0; var disposals = 0
        var torches: [Bool] = []; var focusResets = 0; var preparations = 0
        var geometries: [CGSize] = []
        var preparationError: Error?
        var resumeError: Error?
        var zoomError: Error?
        var holdDisposal = false; var finishDisposal: (() -> Void)?
        func initCamera(completion: @escaping (Error?) -> Void) { isInitialized = true; completion(nil) }
        func prepare(_ settings: ScannerConfiguration, geometry: CGSize, completion: @escaping (Error?) -> Void) {
            preparations += 1
            geometries.append(geometry)
            zooms.append(settings.zoomRatio); isTorchActive = settings.torchEnabled; completion(preparationError)
        }
        func cancelPendingStart(completion: @escaping () -> Void) { completion() }
        func setCamera(_ cameraData: CameraData) throws {}
        func setFlash(_ enabled: Bool) throws { torches.append(enabled); isTorchActive = enabled }
        func resetFocus() { focusResets += 1 }
        func focus(locked: Bool) throws {}
        func resumeCamera(completion: @escaping (Error?) -> Void) { completion(resumeError) }
        func pauseCamera(completion: @escaping () -> Void) { pauses += 1; completion() }
        func setZoomRatio(_ value: Double) throws {
            if let error = zoomError { throw error }
            zooms.append(value)
        }
        func setCropArea(_ cropRect: CropRect) {}
        func updateGeometry(_ size: CGSize) { geometries.append(size) }
        func dispose(completion: @escaping () -> Void) {
            disposals += 1; isInitialized = false
            if holdDisposal { finishDisposal = completion } else { completion() }
        }
    }

    func testIdenticalSettingsSurviveRoundTripBetweenOwners() throws {
        let f = Fixture()
        defer { f.runtime.release() }
        try f.capture(1)

        try f.capture(2)
        try f.capture(1)

        XCTAssertEqual(f.allocations, 1)
        XCTAssertEqual(f.camera.preparations, 1)
        XCTAssertEqual(f.camera.zooms, [1])
        XCTAssertTrue(f.camera.torches.isEmpty)
        XCTAssertEqual(f.camera.focusResets, 0)
    }

    func testChangingOnlyTorchPreservesZoomAndFocus() throws {
        let f = Fixture()
        defer { f.runtime.release() }
        try f.capture(1)

        f.runtime.captureCamera(viewId: 2, configuration: try ScannerConfiguration(arguments: [
            "zoomRatio": 1.0, "torchEnabled": true])) { XCTAssertNil($0) }
        f.permissions.removeFirst()(true)

        XCTAssertEqual(f.camera.preparations, 1)
        XCTAssertEqual(f.camera.zooms, [1])
        XCTAssertEqual(f.camera.torches, [true])
        XCTAssertEqual(f.camera.focusResets, 0)
    }

    func testFailedControlForcesFullSettingsOnNextCapture() throws {
        let f = Fixture()
        defer { f.runtime.release() }
        try f.capture(1)
        f.camera.zoomError = MlKitPluginError.invalidArguments
        XCTAssertThrowsError(try f.runtime.setZoomRatio(value: 2))
        f.camera.zoomError = nil

        try f.capture(2)

        XCTAssertEqual(f.camera.preparations, 2)
        XCTAssertEqual(f.camera.zooms, [1, 1])
    }

    func testIdleTorchChangeIsReappliedForTheNextOwner() throws {
        let f = Fixture()
        defer { f.runtime.release() }
        let configuration = try ScannerConfiguration(arguments: ["zoomRatio": 1.0, "torchEnabled": true])
        f.runtime.captureCamera(viewId: 1, configuration: configuration) { XCTAssertNil($0) }
        f.permissions.removeFirst()(true)
        f.runtime.releaseCapture(completion: {})
        f.camera.isTorchActive = false
        f.runtime.onTorchChanged(f.camera, enabled: false)

        f.runtime.captureCamera(viewId: 2, configuration: configuration) { XCTAssertNil($0) }
        f.permissions.removeFirst()(true)

        XCTAssertEqual(f.camera.torches, [true])
        XCTAssertTrue(f.camera.isTorchActive)
    }

    func testChangedGeometryRestoresFocusOnNextCapture() throws {
        let f = Fixture()
        defer { f.runtime.release() }
        try f.capture(1)
        let geometry = CGSize(width: 400, height: 800)
        try f.runtime.updateGeometry(viewId: 1, size: geometry)

        try f.capture(2, geometry: geometry)

        XCTAssertEqual(f.camera.preparations, 1)
        XCTAssertEqual(f.camera.focusResets, 1)
        XCTAssertEqual(f.camera.zooms, [1])
    }

    func testControlsPreserveKnownErrorCategories() throws {
        let f = Fixture()

        try f.capture(42)
        defer { f.runtime.release() }

        for failure in [MlKitPluginError.invalidArguments, .deviceHasNotZoom, .cameraIsNotInitialized, .deviceHasNotFlash] {
            f.camera.zoomError = failure

            XCTAssertThrowsError(try f.runtime.setZoomRatio(value: 2)) { error in
                XCTAssertEqual(error as? MlKitPluginError, failure)
            }
        }
    }

    func testCameraStartPreservesKnownErrorCategory() throws {
        let f = Fixture()

        f.camera.resumeError = MlKitPluginError.initCameraError
        var received: Error?
        defer { f.runtime.release() }

        f.runtime.captureCamera(viewId: 42, configuration: try ScannerConfiguration(arguments: [
            "zoomRatio": 1.0, "torchEnabled": false])) { received = $0 }
        f.permissions.removeFirst()(true)

        XCTAssertEqual(received as? MlKitPluginError, .initCameraError)
    }

    func testCameraFailurePreservesDescriptionAndNativeCauseWhenAddingConsumer() throws {
        let f = Fixture()

        try f.capture(42)
        let cause = NSError(domain: "CameraTest", code: 7)
        f.camera.zoomError = CameraError(message: "Failed to set camera zoom ratio to 2.0", underlyingError: cause, stackTrace: "native trace")

        XCTAssertThrowsError(try f.runtime.setZoomRatio(value: 2)) { error in
            let failure = error as? CameraError
            XCTAssertEqual(failure?.message, "Failed to set camera zoom ratio to 2.0")
            XCTAssertEqual(failure?.viewId, 42)
            XCTAssertTrue((failure?.underlyingError as? NSError) === cause)
            XCTAssertEqual(failure?.stackTrace, "native trace")
        }
        f.runtime.release()
    }

    func testStartupCameraFailurePreservesDescriptionAndNativeCauseWhenAddingConsumer() throws {
        let f = Fixture()

        let cause = NSError(domain: "CameraTest", code: 7)
        f.camera.preparationError = CameraError(message: "Failed to configure camera", underlyingError: cause, stackTrace: "native trace")
        var received: Error?

        f.runtime.captureCamera(viewId: 42, configuration: try ScannerConfiguration(arguments: ["zoomRatio": 1.0, "torchEnabled": false])) { received = $0 }
        f.permissions.removeFirst()(true)

        let failure = received as? CameraError
        XCTAssertEqual(failure?.message, "Failed to configure camera")
        XCTAssertEqual(failure?.viewId, 42)
        XCTAssertTrue((failure?.underlyingError as? NSError) === cause)
        XCTAssertEqual(failure?.stackTrace, "native trace")
        f.runtime.release()
    }

    func testGeometryUpdatesOnlyTheSelectedCameraAndIsRetainedForHandoff() throws {
        let f = Fixture()


        let compact = CGSize(width: 320.5, height: 240.25)
        let expanded = CGSize(width: 720, height: 1280)
        try f.capture(1, geometry: compact)
        XCTAssertThrowsError(try f.runtime.updateGeometry(viewId: 2, size: expanded))
        XCTAssertEqual(f.camera.geometries, [compact])

        try f.capture(2, geometry: expanded)
        XCTAssertEqual(f.camera.geometries, [compact, expanded])
        try f.runtime.updateGeometry(viewId: 2, size: compact)
        XCTAssertEqual(f.camera.geometries, [compact, expanded, compact])
        f.runtime.release()
    }

    func testPreviewWithdrawalCannotStartReplacementBeforeDisposalCompletes() throws {
        let f = Fixture()

        try f.capture(1)
        f.camera.holdDisposal = true
        f.onPreviewChanged = { description in
            guard description == nil else { return }
            f.onPreviewChanged = nil
            do { try f.capture(2) }
            catch { XCTFail("Replacement capture failed: \(error)") }
        }

        f.runtime.disposeResources(completion: {})
        XCTAssertEqual(f.allocations, 1)
        XCTAssertEqual(f.camera.disposals, 1)
        f.camera.finishDisposal?()
        XCTAssertEqual(f.allocations, 2)
        f.camera.holdDisposal = false
        f.runtime.release()
    }

    func testReleasedCameraCannotPublishPreview() throws {
        let f = Fixture()
        try f.capture(1)
        f.camera.cameraPreviewDelegate?.onPreviewChanged(f.camera,
            description: CameraPreviewDescription(textureId: 1, width: 16, height: 8, state: .streaming))
        XCTAssertEqual(f.previews.count, 1)
        XCTAssertEqual(f.previews[0]?.textureId, 1)
        f.runtime.disposeResources(completion: {})
        XCTAssertEqual(f.previews.count, 2)
        XCTAssertNil(f.previews[1])
        f.camera.cameraPreviewDelegate?.onPreviewChanged(f.camera,
            description: CameraPreviewDescription(textureId: 2, width: 16, height: 8, state: .streaming))
        XCTAssertEqual(f.previews.count, 2)
        f.runtime.release()
    }

    func testInjectedAnalyzerRetainsItsLifetimeAndRoutesOnlyCurrentSubscription() throws {
        let f = Fixture()

        try f.capture(1)
        XCTAssertNil(f.camera.recognitionHandler)
        XCTAssertEqual(f.analyzer.cropUpdates, 1)
        try f.runtime.startScan(delay: 100)
        let old = try XCTUnwrap(f.analyzer.subscription)
        let barcode = ScannerBarcode(rawValue: "test", displayValue: nil, format: 256, valueType: 7)
        old.deliver(barcode)
        XCTAssertEqual(f.resultOwners, [1])

        try f.capture(2)
        XCTAssertNil(f.camera.recognitionHandler)
        XCTAssertEqual(f.analyzer.cropUpdates, 2)
        try f.runtime.startScan(delay: 500)
        old.deliver(barcode)
        XCTAssertEqual(f.resultOwners, [1])
        f.analyzer.subscription?.deliver(barcode)
        XCTAssertEqual(f.resultOwners, [1, 2])
        XCTAssertEqual(f.analyzerAllocations, 1)
        XCTAssertEqual(f.analyzer.delay, 500)
        f.runtime.release()
        XCTAssertNil(f.analyzer.subscription)
    }

    func testHandoffRetainsCameraAndReleaseHasNoNativeGraceTimer() throws {
        let f = Fixture();
        try f.capture(1, zoom: 2); try f.capture(2, zoom: 3)
        XCTAssertEqual(f.allocations, 1); XCTAssertEqual(f.camera.zooms, [2, 3]); XCTAssertEqual(f.camera.pauses, 0)
        f.runtime.releaseCapture(completion: {})
        XCTAssertEqual(f.camera.disposals, 0)
        f.runtime.disposeResources(completion: {})
        XCTAssertEqual(f.camera.disposals, 1)
        f.runtime.release()
    }

    func testReleaseCancelsPermissionAndLateApprovalCannotAllocateCamera() throws {
        let f = Fixture();
        var responses: [Error?] = []
        f.runtime.captureCamera(viewId: 1, configuration: try config()) { responses.append($0) }
        f.runtime.releaseCapture(completion: {})
        XCTAssertEqual(responses.count, 1); XCTAssertNotNil(responses[0])
        f.permissions.removeFirst()(true)
        XCTAssertEqual(f.allocations, 0); XCTAssertEqual(responses.count, 1)
        f.runtime.release()
    }

    func testBackgroundStopsHardwareAndForegroundRequiresANewCapture() throws {
        let f = Fixture()
        defer { f.runtime.release() }
        try f.capture(1)
        try f.runtime.startScan(delay: 0)
        let previous = try XCTUnwrap(f.analyzer.subscription)
        let barcode = ScannerBarcode(rawValue: "test", displayValue: nil, format: 256, valueType: 7)

        f.notifications.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        f.notifications.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        previous.deliver(barcode)
        try f.runtime.startScan(delay: 0)

        XCTAssertEqual(f.camera.pauses, 1)
        XCTAssertEqual(f.camera.disposals, 0)
        XCTAssertNil(f.camera.recognitionHandler)
        XCTAssertNil(f.analyzer.subscription)
        XCTAssertTrue(f.resultOwners.isEmpty)

        try f.capture(1)
        try f.runtime.startScan(delay: 0)
        f.analyzer.subscription?.deliver(barcode)

        XCTAssertEqual(f.resultOwners, [1])
        XCTAssertEqual(f.allocations, 1)
    }

    func testReplacementCaptureIgnoresLatePermissionForPreviousOwner() throws {
        let f = Fixture()
        defer { f.runtime.release() }
        var previousReplies: [Error?] = []
        var currentReplies: [Error?] = []
        f.runtime.captureCamera(viewId: 1, configuration: try config()) { previousReplies.append($0) }
        f.runtime.captureCamera(viewId: 2, configuration: try config()) { currentReplies.append($0) }

        XCTAssertEqual(previousReplies.count, 1)
        XCTAssertEqual(previousReplies[0] as? MlKitPluginError, .cameraSessionDisposed)
        f.permissions.removeFirst()(true)

        XCTAssertEqual(f.allocations, 0)
        XCTAssertTrue(currentReplies.isEmpty)
        f.permissions.removeFirst()(true)

        XCTAssertEqual(f.allocations, 1)
        XCTAssertEqual(currentReplies.count, 1)
        XCTAssertNil(currentReplies[0])
        XCTAssertEqual(previousReplies.count, 1)
        XCTAssertThrowsError(try f.runtime.updateGeometry(viewId: 1, size: CGSize(width: 20, height: 20)))
        XCTAssertNoThrow(try f.runtime.updateGeometry(viewId: 2, size: CGSize(width: 20, height: 20)))
    }

    func testDeniedPermissionAllocatesNoHardwareAndAllowsANewCapture() throws {
        let f = Fixture()
        defer { f.runtime.release() }
        var replies: [Error?] = []
        f.runtime.captureCamera(viewId: 1, configuration: try config()) { replies.append($0) }

        f.permissions.removeFirst()(false)

        XCTAssertEqual(replies.count, 1)
        XCTAssertEqual(replies[0] as? MlKitPluginError, .authorizationCameraError)
        XCTAssertEqual(f.allocations, 0)
        XCTAssertEqual(f.analyzerAllocations, 0)

        try f.capture(2)

        XCTAssertEqual(f.allocations, 1)
        XCTAssertEqual(f.analyzerAllocations, 1)
    }

    func testPermanentReleaseDisposesOnceAndRejectsLateResultsAndRestart() throws {
        let f = Fixture()
        try f.capture(1)
        try f.runtime.startScan(delay: 0)
        let previous = try XCTUnwrap(f.analyzer.subscription)
        var replies: [Error?] = []

        f.runtime.release()
        f.runtime.release()
        previous.deliver(ScannerBarcode(rawValue: "late", displayValue: nil, format: 256, valueType: 7))
        f.notifications.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        f.runtime.captureCamera(viewId: 2, configuration: try config()) { replies.append($0) }
        try f.runtime.startScan(delay: 0)

        XCTAssertEqual(f.camera.disposals, 1)
        XCTAssertEqual(f.camera.pauses, 0)
        XCTAssertEqual(f.allocations, 1)
        XCTAssertTrue(f.permissions.isEmpty)
        XCTAssertNil(f.analyzer.subscription)
        XCTAssertNil(f.camera.recognitionHandler)
        XCTAssertTrue(f.resultOwners.isEmpty)
        XCTAssertEqual(replies.count, 1)
        XCTAssertEqual(replies[0] as? MlKitPluginError, .cameraSessionDisposed)
    }

    func testReleaseBeforeFirstCaptureDoesNotAllocateResourcesOrRequestPermission() throws {
        let f = Fixture()
        var received: Error?

        f.runtime.release()
        f.runtime.captureCamera(viewId: 1, configuration: try config()) { received = $0 }

        XCTAssertEqual(received as? MlKitPluginError, .cameraSessionDisposed)
        XCTAssertEqual(f.allocations, 0)
        XCTAssertEqual(f.analyzerAllocations, 0)
        XCTAssertTrue(f.permissions.isEmpty)
    }

    func testDisposalAcknowledgmentWaitsForNativeResourceRelease() throws {
        let f = Fixture(); try f.capture(1)
        f.camera.holdDisposal = true
        var replies = 0
        f.runtime.disposeResources { replies += 1 }
        f.runtime.disposeResources { replies += 1 }
        XCTAssertEqual(replies, 0); XCTAssertEqual(f.camera.disposals, 1)
        f.camera.finishDisposal?(); XCTAssertEqual(replies, 2)
        f.runtime.release()
    }

    func testNewCaptureWaitsForPendingHardwareDisposal() throws {
        let f = Fixture(); try f.capture(1)
        f.camera.holdDisposal = true
        f.runtime.disposeResources(completion: {})
        f.runtime.captureCamera(viewId: 1, configuration: try config()) { XCTAssertNil($0) }
        f.permissions.removeFirst()(true)
        XCTAssertEqual(f.allocations, 1)
        f.camera.finishDisposal?(); XCTAssertEqual(f.allocations, 2)
        f.camera.holdDisposal = false; f.runtime.release()
    }

    private func config() throws -> ScannerConfiguration { try ScannerConfiguration(arguments: [
        "zoomRatio": 1, "torchEnabled": false]) }
}
