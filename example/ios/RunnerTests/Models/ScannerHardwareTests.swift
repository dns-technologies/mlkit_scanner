import UIKit
import CoreMedia
import XCTest
@testable import mlkit_scanner

final class ScannerHardwareTests: XCTestCase {
    func testPreviewWithdrawalCannotStartReplacementBeforeDisposalCompletes() throws {
        let f = Fixture()
        f.runtime.register(viewId: 1); f.runtime.register(viewId: 2)
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
        f.runtime.register(viewId: 1); try f.capture(1)
        f.camera.cameraPreviewDelegate?.onPreviewChanged(f.camera, description: ["textureId": 1])
        XCTAssertEqual(f.previews.count, 1)
        XCTAssertEqual(f.previews[0]?["textureId"] as? Int, 1)
        f.runtime.disposeResources(completion: {})
        XCTAssertEqual(f.previews.count, 2)
        XCTAssertNil(f.previews[1])
        f.camera.cameraPreviewDelegate?.onPreviewChanged(f.camera, description: ["textureId": 2])
        XCTAssertEqual(f.previews.count, 2)
        f.runtime.release()
    }

    func testInjectedAnalyzerRetainsItsLifetimeAndRoutesOnlyCurrentSubscription() throws {
        let f = Fixture()
        f.runtime.register(viewId: 1); f.runtime.register(viewId: 2)
        try f.capture(1)
        XCTAssertNil(f.camera.recognitionHandler)
        XCTAssertEqual(f.analyzer.cropUpdates, 1)
        try f.runtime.startScan(type: .barcodeRecognition, delay: 100)
        let old = try XCTUnwrap(f.analyzer.subscription)
        let barcode = ScannerBarcode(rawValue: "test", displayValue: nil, format: 256, valueType: 7)
        old.deliver(barcode)
        XCTAssertEqual(f.resultOwners, [1])

        try f.capture(2)
        XCTAssertNil(f.camera.recognitionHandler)
        XCTAssertEqual(f.analyzer.cropUpdates, 2)
        try f.runtime.startScan(type: .barcodeRecognition, delay: 500)
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
        let f = Fixture(); f.runtime.register(viewId: 1); f.runtime.register(viewId: 2)
        try f.capture(1, zoom: 2); try f.capture(2, zoom: 3)
        XCTAssertEqual(f.allocations, 1); XCTAssertEqual(f.camera.zooms, [2, 3]); XCTAssertEqual(f.camera.pauses, 0)
        f.runtime.releaseCamera(completion: {})
        XCTAssertEqual(f.camera.disposals, 0)
        f.runtime.disposeResources(completion: {})
        XCTAssertEqual(f.camera.disposals, 1)
        f.runtime.release()
    }
    func testReleaseCancelsPermissionAndLateApprovalCannotAllocateCamera() throws {
        let f = Fixture(); f.runtime.register(viewId: 1)
        var responses: [Error?] = []
        f.runtime.captureCamera(viewId: 1, configuration: try config()) { responses.append($0) }
        f.runtime.releaseCamera(completion: {})
        XCTAssertEqual(responses.count, 1); XCTAssertNotNil(responses[0])
        f.permissions.removeFirst()(true)
        XCTAssertEqual(f.allocations, 0); XCTAssertEqual(responses.count, 1)
        f.runtime.release()
    }
    func testBackgroundStopsHardwareWithoutWaitingForDart() throws {
        let f = Fixture(); f.runtime.register(viewId: 1); try f.capture(1)
        f.notifications.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        XCTAssertEqual(f.camera.pauses, 1)
        XCTAssertEqual(f.camera.disposals, 0)
        f.runtime.release()
    }
    func testDisposalAcknowledgmentWaitsForNativeResourceRelease() throws {
        let f = Fixture(); f.runtime.register(viewId: 1); try f.capture(1)
        f.camera.holdDisposal = true
        var replies = 0
        f.runtime.disposeResources { replies += 1 }
        f.runtime.disposeResources { replies += 1 }
        XCTAssertEqual(replies, 0); XCTAssertEqual(f.camera.disposals, 1)
        f.camera.finishDisposal?(); XCTAssertEqual(replies, 2)
        f.runtime.release()
    }
    func testNewCaptureWaitsForPendingHardwareDisposal() throws {
        let f = Fixture(); f.runtime.register(viewId: 1); try f.capture(1)
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

    private final class Fixture {
        let camera = TestCamera(); let notifications = NotificationCenter()
        let analyzer = TestAnalyzer()
        var analyzerAllocations = 0
        var resultOwners: [Int64] = []
        var previews: [[String: Any]?] = []
        var onPreviewChanged: (([String: Any]?) -> Void)?
        var allocations = 0; var permissions: [(Bool) -> Void] = []
        lazy var runtime = ScannerHardware(onScanResult: { [unowned self] viewId, _ in self.resultOwners.append(viewId) }, onTorchChanged: { _, _ in },
            onPreviewChanged: { [unowned self] in self.previews.append($0); self.onPreviewChanged?($0) },
            notificationCenter: notifications, cameraFactory: { [unowned self] in self.allocations += 1; return self.camera },
            analyzerFactory: { [unowned self] in self.analyzerAllocations += 1; return self.analyzer },
            requestPermission: { [unowned self] in self.permissions.append($0) })
        func capture(_ id: Int64, zoom: Double = 1) throws {
            runtime.captureCamera(viewId: id, configuration: try ScannerConfiguration(arguments: [
                "zoomRatio": zoom, "torchEnabled": false])) { XCTAssertNil($0) }
            permissions.removeFirst()(true)
        }
    }

    private final class TestAnalyzer: BarcodeAnalyzing, RecognitionHandler {
        var type: RecognitionType = .barcodeRecognition
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
        var holdDisposal = false; var finishDisposal: (() -> Void)?
        func initCamera(completion: @escaping (Error?) -> Void) { isInitialized = true; completion(nil) }
        func prepare(_ settings: ScannerConfiguration, geometry: CGSize, completion: @escaping (Error?) -> Void) {
            zooms.append(settings.zoomRatio); isTorchActive = settings.torchEnabled; completion(nil)
        }
        func cancelPendingStart(completion: @escaping () -> Void) { completion() }
        func setCamera(_ cameraData: CameraData) throws {}
        func setFlash(_ enabled: Bool) throws { isTorchActive = enabled }
        func resetFocus() {}
        func focus(locked: Bool) throws {}
        func resumeCamera(completion: @escaping (Error?) -> Void) { completion(nil) }
        func pauseCamera(completion: @escaping () -> Void) { pauses += 1; completion() }
        func setZoomRatio(_ value: Double) throws { zooms.append(value) }
        func setCropArea(_ cropRect: CropRect) {}
        func updateGeometry(_ size: CGSize) {}
        func dispose(completion: @escaping () -> Void) {
            disposals += 1; isInitialized = false
            if holdDisposal { finishDisposal = completion } else { completion() }
        }
    }
}
