import AVFoundation
import CoreMedia
import UIKit
import XCTest
@testable import mlkit_scanner

final class CameraPreviewTests: XCTestCase {
    func testViewIdentityAndInitialLayoutAreExposedToFlutter() {
        let preview = CameraPreview(
            frame: CGRect(x: 0, y: 0, width: 200, height: 100)
        )

        XCTAssertEqual(preview.view().frame, CGRect(x: 0, y: 0, width: 200, height: 100))
        XCTAssertTrue(preview.isLayoutReady)
        XCTAssertTrue(preview.view() === preview.view())
        preview.dispose()
    }

    func testPendingLayoutCompletionRunsOnceBoundsBecomeUsable() {
        let preview = CameraPreview(frame: .zero)
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
            frame: CGRect(x: 0, y: 0, width: 200, height: 100)
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
        let preview = CameraPreview(frame: .zero)
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

    func testCancelPendingStartAndResumeCompleteForAnUninitializedPreview() {
        let preview = CameraPreview(frame: .zero)
        let pauseExpectation = expectation(description: "pause")
        let resumeExpectation = expectation(description: "resume")
        var resumeError: Error?

        preview.cancelPendingStart { pauseExpectation.fulfill() }
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
        let preview = CameraPreview(frame: .zero)
        var handler: TestRecognitionHandler? = TestRecognitionHandler(
            delay: 0,
            cropRect: nil
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

    init(delay: Int, cropRect: CropRect?) {}

    func setDelay(delay: Int) {}

    func processVideoOutput(
        sampleBuffer: CMSampleBuffer,
        scaleX: CGFloat,
        scaleY: CGFloat,
        orientation: AVCaptureVideoOrientation
    ) {}

    func updateCropRect(cropRect: CropRect) {}
}
