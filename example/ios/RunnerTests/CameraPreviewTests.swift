import AVFoundation
import CoreMedia
import UIKit
import XCTest
@testable import mlkit_scanner

final class CameraPreviewTests: XCTestCase {
    func testViewIdentityAndInitialLayoutAreExposedToFlutter() {
        let token = UUID()
        let preview = CameraPreview(
            frame: CGRect(x: 0, y: 0, width: 200, height: 100),
            viewId: 42,
            registrationToken: token
        )

        XCTAssertEqual(preview.viewId, 42)
        XCTAssertEqual(preview.registrationToken, token)
        XCTAssertEqual(preview.view().frame, CGRect(x: 0, y: 0, width: 200, height: 100))
        XCTAssertTrue(preview.isLayoutReady)
        XCTAssertTrue(preview.view() === preview.view())
        preview.dispose()
    }

    func testPendingLayoutCompletionRunsOnceBoundsBecomeUsable() {
        let preview = CameraPreview(frame: .zero, viewId: 42)
        var completionCount = 0

        preview.whenLayoutReady { completionCount += 1 }
        XCTAssertEqual(completionCount, 0)

        preview.view().frame = CGRect(x: 0, y: 0, width: 200, height: 100)
        preview.view().layoutIfNeeded()

        XCTAssertTrue(preview.isLayoutReady)
        XCTAssertEqual(completionCount, 1)
        preview.dispose()
    }

    func testCropCreatesOneReusableOverlayAndUpdatesItsActiveState() throws {
        let preview = CameraPreview(
            frame: CGRect(x: 0, y: 0, width: 200, height: 100),
            viewId: 42
        )

        preview.setCropArea(try CropRect(arguments: ["scaleWidth": 0.5]))
        preview.setCropArea(try CropRect(arguments: ["scaleWidth": 0.75]))
        preview.setScanActive(true)

        let overlays = preview.view().subviews.compactMap { $0 as? ScannerOverlay }
        XCTAssertEqual(overlays.count, 1)
        XCTAssertEqual(overlays.first?.cropRect.scaleWidth, 0.75)
        XCTAssertEqual(overlays.first?.isActive, true)
        preview.dispose()
    }

    func testCameraControlsFailClearlyBeforeInitialization() {
        let preview = CameraPreview(frame: .zero, viewId: 42)
        let camera = CameraData(type: .builtInWideAngleCamera, position: .back)

        XCTAssertThrowsError(try preview.setCamera(camera)) {
            XCTAssertEqual($0 as? MlKitPluginError, .cameraIsNotInitialized)
        }
        XCTAssertThrowsError(try preview.setFlash(true)) {
            XCTAssertEqual($0 as? MlKitPluginError, .cameraIsNotInitialized)
        }
        XCTAssertThrowsError(try preview.setZoomRatio(2)) {
            XCTAssertEqual($0 as? MlKitPluginError, .cameraIsNotInitialized)
        }
        preview.dispose()
    }

    func testPauseAndResumeCompleteWithoutRacingAnUninitializedPreview() {
        let preview = CameraPreview(frame: .zero, viewId: 42)
        let pauseExpectation = expectation(description: "pause")
        let resumeExpectation = expectation(description: "resume")
        var resumeError: Error?

        preview.pauseCamera { pauseExpectation.fulfill() }
        preview.resumeCamera {
            resumeError = $0
            resumeExpectation.fulfill()
        }

        wait(for: [pauseExpectation, resumeExpectation], timeout: 2)
        XCTAssertEqual(resumeError as? MlKitPluginError, .cameraIsNotInitialized)
        preview.dispose()
        preview.dispose()
    }

    func testRecognitionHandlerReferenceIsWeakAndThreadSafeAtBoundary() {
        let preview = CameraPreview(frame: .zero, viewId: 42)
        var handler: TestRecognitionHandler? = TestRecognitionHandler(
            delay: 0,
            cropRect: nil,
            viewId: 42
        )

        preview.recognitionHandler = handler
        XCTAssertTrue(preview.recognitionHandler === handler)

        handler = nil
        XCTAssertNil(preview.recognitionHandler)
        preview.dispose()
    }
}

private final class TestRecognitionHandler: RecognitionHandler {
    let type = RecognitionType.barcodeRecognition
    weak var delegate: RecognitionResultDelegate?

    required init(delay: Int, cropRect: CropRect?, viewId: Int64) {}

    func setDelay(delay: Int) {}

    func processVideoOutput(
        sampleBuffer: CMSampleBuffer,
        scaleX: CGFloat,
        scaleY: CGFloat,
        orientation: AVCaptureVideoOrientation
    ) {}

    func updateCropRect(cropRect: CropRect) {}
}
