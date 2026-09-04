import XCTest
@testable import mlkit_scanner

final class ScannerViewStateTests: XCTestCase {
    func testInitializerRetainsRegistrationIntentAndPreviewIdentity() throws {
        let token = UUID()
        let preview = CameraPreview(
            frame: .zero,
            viewId: 42,
            registrationToken: token
        )
        let crop = try CropRect(arguments: ["scaleWidth": 0.5])
        let camera = CameraData(type: .builtInWideAngleCamera, position: .back)
        let registration = ScannerViewRegistration(
            size: CGSize(width: 200, height: 100),
            initialZoomRatio: 2,
            initialFlashEnabled: true,
            initialCropRect: crop,
            initialCamera: camera
        )

        let state = ScannerViewState(
            viewId: 42,
            view: preview,
            registration: registration
        )

        XCTAssertEqual(state.viewId, 42)
        XCTAssertEqual(state.registrationToken, token)
        XCTAssertTrue(state.view === preview)
        XCTAssertEqual(state.zoomRatio, 2)
        XCTAssertEqual(state.torchEnabled, true)
        XCTAssertEqual(state.cropArea?.scaleWidth, 0.5)
        XCTAssertEqual(state.camera?.type, .builtInWideAngleCamera)
        XCTAssertEqual(state.camera?.position, .back)
        XCTAssertTrue(state.cameraRequested)
        XCTAssertFalse(state.isCameraOwner)
        XCTAssertTrue(state.captureCompletions.isEmpty)
        preview.dispose()
    }
}
