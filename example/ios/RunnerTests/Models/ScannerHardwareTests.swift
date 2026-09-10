import AVFoundation
import UIKit
import XCTest
@testable import mlkit_scanner

/// Hardware-free tests for the native bridge; Dart tests own route/configuration policy.
final class ScannerHardwareTests: XCTestCase {
    func testRegistrationDoesNotAllocateHardware() {
        let f = Fixture()
        let view = f.view(42)
        XCTAssertEqual(view.viewId, 42)
        XCTAssertEqual(f.allocations, 0)
        f.runtime.release()
    }

    func testCaptureWaitsForPermissionAndFirstFrame() throws {
        let f = Fixture()
        let view = f.view(42)
        var replies = 0
        f.runtime.captureCamera(viewId: view.viewId, configuration: try configuration()) {
            XCTAssertNil($0); replies += 1
        }
        XCTAssertEqual(f.allocations, 0)
        f.permissions.removeFirst()(true)
        XCTAssertEqual(f.allocations, 1)
        XCTAssertEqual(replies, 0)
        f.camera.starts.removeFirst()(nil)
        XCTAssertEqual(replies, 1)
        XCTAssertTrue(f.camera.preview.superview === view.view())
        f.runtime.release()
    }

    func testPermissionDenialDoesNotCreateSDK() throws {
        let f = Fixture()
        let view = f.view(42)
        var failure: Error?
        f.runtime.captureCamera(viewId: view.viewId, configuration: try configuration()) { failure = $0 }
        f.permissions.removeFirst()(false)
        XCTAssertEqual(failure as? MlKitPluginError, .authorizationCameraError)
        XCTAssertEqual(f.allocations, 0)
        f.runtime.release()
    }

    func testReleaseInterruptsPermissionAndIgnoresLateGrant() throws {
        let f = Fixture()
        let view = f.view(42)
        var replies = 0
        f.runtime.captureCamera(viewId: view.viewId, configuration: try configuration()) { _ in replies += 1 }
        f.runtime.releaseCamera() {}
        f.permissions.removeFirst()(true)
        XCTAssertEqual(replies, 1)
        XCTAssertEqual(f.allocations, 0)
        f.runtime.release()
    }

    func testCaptureAppliesConfigurationBeforeAcknowledgementAndAnalysis() throws {
        let f = Fixture()
        let view = f.view(42)
        try f.activate(view, configuration())
        var completed = false
        f.runtime.releaseCamera() {}
        f.runtime.captureCamera(viewId: view.viewId, configuration: try configuration(zoom: 8, scanning: true, torch: true)) {
            XCTAssertNil($0)
            completed = true
        }
        f.permissions.removeFirst()(true)
        XCTAssertEqual(f.allocations, 1)
        XCTAssertTrue(f.permissions.isEmpty)
        XCTAssertTrue(f.camera.preview.superview === view.view())
        XCTAssertNil(f.camera.recognitionHandler)
        XCTAssertEqual(f.camera.zooms, [1, 8])
        XCTAssertEqual(f.camera.torches, [false, true])
        XCTAssertFalse(completed)
        f.camera.starts.removeFirst()(nil)
        XCTAssertTrue(completed)
        XCTAssertNotNil(f.camera.recognitionHandler)
        f.runtime.release()
    }

    func testPointControlsKeepPreviewAttachedWithoutResettingFocusOrAnalysis() throws {
        let f = Fixture()
        let view = f.view(42)
        try f.activate(view, configuration(scanning: true))
        let analyzer = f.camera.recognitionHandler
        let focusResets = f.camera.focusResets
        try f.runtime.setZoomRatio(value: 8)
        try f.runtime.setTorch(enabled: true)
        try f.runtime.updateScanPeriod(delay: 200)
        try f.runtime.setCropArea(cropRect: CropRect(arguments: ["scaleWidth": 0.5]))
        XCTAssertTrue(f.camera.preview.superview === view.view())
        XCTAssertTrue(f.camera.recognitionHandler === analyzer)
        XCTAssertEqual(f.camera.focusResets, focusResets)
        XCTAssertTrue(f.camera.starts.isEmpty)
        XCTAssertTrue(f.permissions.isEmpty)
        XCTAssertEqual(f.camera.zooms, [1, 8])
        XCTAssertEqual(f.camera.torches, [false, true])
        f.runtime.release()
    }

    func testSwitchReusesHardwareAndAppliesCompleteSnapshot() throws {
        let f = Fixture()
        let a = f.view(42)
        let b = f.view(43)
        try f.activate(a, configuration())
        f.runtime.releaseCamera() {}
        try f.activate(b, configuration(zoom: 4))
        XCTAssertEqual(f.allocations, 1)
        XCTAssertEqual(f.camera.zooms, [1, 4])
        XCTAssertTrue(f.camera.preview.superview === b.view())
        f.runtime.release()
    }

    func testReleaseCompletesAnUnfinishedStartOnlyOnce() throws {
        let f = Fixture()
        let view = f.view(42)
        var replies = 0
        f.runtime.captureCamera(viewId: view.viewId, configuration: try configuration()) { _ in replies += 1 }
        f.permissions.removeFirst()(true)
        let lateStart = f.camera.starts.removeFirst()
        f.runtime.releaseCamera() {}
        lateStart(nil)
        XCTAssertEqual(replies, 1)
        XCTAssertNil(f.camera.preview.superview)
        f.runtime.release()
    }

    func testIdleDisposalReleasesHardwareButNotFlutterView() throws {
        let f = Fixture()
        let view = f.view(42)
        try f.activate(view, configuration())
        f.runtime.releaseCamera() {}
        XCTAssertEqual(f.camera.disposals, 0)
        let disposed = expectation(description: "idle hardware disposed")
        let releasedAt = ProcessInfo.processInfo.systemUptime
        f.camera.onDispose = {
            XCTAssertGreaterThanOrEqual(ProcessInfo.processInfo.systemUptime - releasedAt, 0.29)
            disposed.fulfill()
        }
        wait(for: [disposed], timeout: 2)
        XCTAssertEqual(f.camera.disposals, 1)
        XCTAssertEqual(view.viewId, 42)
        f.camera.onDispose = nil
        try f.activate(view, configuration(zoom: 3))
        XCTAssertEqual(f.allocations, 2)
        XCTAssertTrue(f.camera.preview.superview === view.view())
        f.runtime.release()
    }

    func testCaptureCancelsExpiryBeforePermissionCompletes() throws {
        let f = Fixture()
        let view = f.view(42)
        try f.activate(view, configuration())
        f.runtime.releaseCamera() {}
        f.runtime.captureCamera(viewId: view.viewId, configuration: try configuration()) { XCTAssertNil($0) }
        let elapsed = expectation(description: "old grace elapsed while permission is pending")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { elapsed.fulfill() }
        wait(for: [elapsed], timeout: 2)
        XCTAssertEqual(f.camera.disposals, 0)
        f.permissions.removeFirst()(true)
        f.camera.starts.removeFirst()(nil)
        XCTAssertEqual(f.allocations, 1)
        f.runtime.release()
    }

    func testReturningConsumerCancelsNativeExpiry() throws {
        let f = Fixture()
        let view = f.view(42)
        try f.activate(view, configuration())
        f.runtime.releaseCamera() {}
        try f.activate(view, configuration())
        let elapsed = expectation(description: "old grace elapsed")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { elapsed.fulfill() }
        wait(for: [elapsed], timeout: 2)
        XCTAssertEqual(f.camera.disposals, 0)
        f.runtime.release()
    }

    func testViewDeallocationReleasesBorrowedPreview() throws {
        let f = Fixture()
        try autoreleasepool {
            let view = f.view(42)
            try f.activate(view, configuration())
        }
        XCTAssertNil(f.camera.preview.superview)
        XCTAssertEqual(f.camera.disposals, 0)
        let disposed = expectation(description: "deallocated view triggers idle cleanup")
        f.camera.onDispose = { disposed.fulfill() }
        wait(for: [disposed], timeout: 2)
        XCTAssertEqual(f.camera.disposals, 1)
        f.runtime.release()
    }

    func testViewDeallocationCancelsPermissionWaitWithoutWaitingForDialog() throws {
        let f = Fixture()
        var replies = 0
        try autoreleasepool {
            let view = f.view(42)
            f.runtime.captureCamera(viewId: view.viewId, configuration: try configuration()) { _ in replies += 1 }
        }
        XCTAssertEqual(replies, 1)
        f.permissions.removeFirst()(true)
        XCTAssertEqual(f.allocations, 0)
        f.runtime.release()
    }

    func testBackgroundDisposesHardwareWithoutWaitingForDart() throws {
        let f = Fixture()
        let view = f.view(42)
        try f.activate(view, configuration())
        f.notifications.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        XCTAssertEqual(f.camera.disposals, 1)
        XCTAssertNil(f.camera.preview.superview)
        f.runtime.release()
    }

    func testPendingMessageCannotRestartHardwareAfterBackgroundNotification() throws {
        let f = Fixture()
        let view = f.view(42)
        f.notifications.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        f.runtime.captureCamera(viewId: view.viewId, configuration: try configuration()) { XCTAssertNil($0) }
        XCTAssertTrue(f.permissions.isEmpty)
        XCTAssertEqual(f.allocations, 0)
        f.notifications.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        try f.activate(view, configuration())
        XCTAssertEqual(f.allocations, 1)
        f.runtime.release()
    }

    func testFirstFrameFailurePreservesTypedAwaitOpenError() throws {
        let f = Fixture()
        let view = f.view(42)
        var received: Error?
        f.runtime.captureCamera(viewId: view.viewId, configuration: try configuration()) { received = $0 }
        f.permissions.removeFirst()(true)
        f.camera.starts.removeFirst()(MlKitPluginError.initCameraError)
        let error = try XCTUnwrap(received as? CameraControlError)
        XCTAssertEqual(error.operation, .awaitOpen)
        XCTAssertEqual(error.viewId, 42)
        f.runtime.release()
    }

    func testInterruptedStartCanRetryWithoutLateCompletionChangingNewCapture() throws {
        let f = Fixture()
        let view = f.view(42)
        var replies = 0
        f.runtime.captureCamera(viewId: view.viewId, configuration: try configuration(scanning: true)) {
            XCTAssertNotNil($0 as? CameraControlError)
            replies += 1
        }
        f.permissions.removeFirst()(true)
        let interrupted = f.camera.starts.removeFirst()
        interrupted(MlKitPluginError.initCameraError)
        XCTAssertEqual(replies, 1)
        XCTAssertNil(f.camera.recognitionHandler)
        f.runtime.releaseCamera() {}
        try f.activate(view, configuration(zoom: 3))
        interrupted(nil)
        XCTAssertEqual(replies, 1)
        XCTAssertEqual(f.camera.zooms, [1, 3])
        XCTAssertNil(f.camera.recognitionHandler)
        f.runtime.release()
    }

    func testRapidABARejectsOldFirstFrameCallbacksForTheSameView() throws {
        let f = Fixture()
        let a = f.view(42)
        let b = f.view(43)
        var replies = 0
        f.runtime.captureCamera(viewId: a.viewId, configuration: try configuration(scanning: true)) {
            XCTAssertNil($0); replies += 1
        }
        f.permissions.removeFirst()(true)
        let firstA = f.camera.starts.removeFirst()
        f.runtime.releaseCamera() {}
        f.runtime.captureCamera(viewId: b.viewId, configuration: try configuration(zoom: 2)) {
            XCTAssertNil($0); replies += 1
        }
        f.permissions.removeFirst()(true)
        let firstB = f.camera.starts.removeFirst()
        f.runtime.releaseCamera() {}
        f.runtime.captureCamera(viewId: a.viewId, configuration: try configuration(zoom: 3)) {
            XCTAssertNil($0); replies += 1
        }
        f.permissions.removeFirst()(true)
        firstA(nil)
        firstB(MlKitPluginError.initCameraError)
        XCTAssertEqual(replies, 2)
        XCTAssertNil(f.camera.recognitionHandler)
        f.camera.starts.removeFirst()(nil)
        XCTAssertEqual(replies, 3)
        XCTAssertEqual(f.camera.zooms, [1, 2, 3])
        XCTAssertTrue(f.camera.preview.superview === a.view())
        f.runtime.release()
    }

    func testReleaseCancelsLayoutWaitWithoutStartingTheNextConsumerEarly() throws {
        let f = Fixture()
        let a = f.view(42)
        let b = f.view(43)
        f.camera.isLayoutReady = false
        f.runtime.captureCamera(viewId: a.viewId, configuration: try configuration()) { XCTAssertNil($0) }
        f.permissions.removeFirst()(true)
        let oldLayout = f.camera.layouts.removeFirst()
        f.runtime.releaseCamera() {}
        f.runtime.captureCamera(viewId: b.viewId, configuration: try configuration()) { XCTAssertNil($0) }
        f.permissions.removeFirst()(true)
        oldLayout()
        XCTAssertTrue(f.camera.starts.isEmpty)
        f.camera.layouts.removeFirst()()
        XCTAssertEqual(f.camera.starts.count, 1)
        f.camera.starts.removeFirst()(nil)
        XCTAssertTrue(f.camera.preview.superview === b.view())
        f.runtime.release()
    }

    private func configuration(zoom: Double = 1, scanning: Bool = false, torch: Bool = false) throws -> ScannerConfiguration {
        try ScannerConfiguration(arguments: [
            "zoomRatio": zoom, "torchEnabled": torch, "scanEnabled": scanning, "scanDelay": 0,
        ])
    }

    private final class Fixture {
        let camera = TestCamera()
        let notifications = NotificationCenter()
        var permissions: [(Bool) -> Void] = []
        var allocations = 0
        lazy var runtime = ScannerHardware(onScanResult: { _, _ in }, onTorchChanged: { _, _ in },
            notificationCenter: notifications, cameraFactory: { [unowned self] in
                self.allocations += 1
                return self.camera
            }, requestPermission: { [unowned self] in self.permissions.append($0) })

        func view(_ id: Int64) -> ScannerView {
            runtime.createView(frame: CGRect(x: 0, y: 0, width: 200, height: 100), viewId: id)
        }

        func activate(_ view: ScannerView, _ configuration: ScannerConfiguration) throws {
            runtime.captureCamera(viewId: view.viewId, configuration: configuration) { XCTAssertNil($0) }
            permissions.removeFirst()(true)
            camera.starts.removeFirst()(nil)
        }
    }

    private final class TestCamera: CameraPreviewing {
        let preview = UIView()
        var isInitialized = false
        var isTorchActive: Bool { torches.last == true }
        var isLayoutReady = true
        var recognitionHandler: RecognitionHandler?
        weak var cameraPreviewDelegate: CameraPreviewDelegate?
        var starts: [ScannerCompletion] = []
        var layouts: [() -> Void] = []
        var zooms: [Double] = []
        var torches: [Bool] = []
        var disposals = 0
        var onDispose: (() -> Void)?
        var focusResets = 0

        func view() -> UIView { preview }
        func initCamera(completion: @escaping ScannerCompletion) { isInitialized = true; completion(nil) }
        func whenLayoutReady(_ completion: @escaping () -> Void) {
            if isLayoutReady { completion() } else { layouts.append(completion) }
        }
        func cancelPendingStart(completion: @escaping () -> Void) {
            layouts.removeAll()
            let pending = starts
            starts.removeAll()
            pending.forEach { $0(nil) }
            completion()
        }
        func resumeCamera(completion: @escaping ScannerCompletion) { starts.append(completion) }
        func setCamera(_ cameraData: CameraData) throws {}
        func setFlash(_ enabled: Bool) throws { torches.append(enabled) }
        func setZoomRatio(_ value: Double) throws { zooms.append(value) }
        func setCropArea(_ cropRect: CropRect) {}
        func setScanActive(_ isActive: Bool) {}
        func resetFocus() { focusResets += 1 }
        func dispose() { disposals += 1; isInitialized = false; onDispose?() }
    }
}
