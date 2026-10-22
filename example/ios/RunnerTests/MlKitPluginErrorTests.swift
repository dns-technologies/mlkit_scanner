import XCTest
@testable import mlkit_scanner

final class MlKitPluginErrorTests: XCTestCase {
    func testPluginErrorsExposeStableCodesAndDescriptions() {
        let expected: [(MlKitPluginError, String, String)] = [
            (.initCameraError, "1", "Internal camera initialisation error"),
            (.authorizationCameraError, "2", "The app does not have camera permission"),
            (.cameraIsNotInitialized, "3", "Camera platform view is not created"),
            (.deviceHasNotFlash, "4", "Device has no flash"),
            (.invalidArguments, "5", "Invalid scanner arguments"),
            (.deviceHasNotZoom, "6", "Zoom is not supported on this device"),
            (.unknownError, "7", "Unknown scanner error"),
            (.cameraSessionDisposed, "8", "Camera session has been disposed"),
            (.cameraError, "9", "Camera operation failed"),
        ]

        for (error, code, description) in expected {
            XCTAssertEqual(error.rawValue, code)
            XCTAssertEqual(error.localizedDescription, description)
        }
    }

    func testCameraErrorSerializesAvailableDiagnosticContext() {
        let error = CameraError(
            message: "Camera failed to open",
            viewId: 12,
            underlyingError: PluginErrorTestCause.rejected,
            cameraStateErrorCode: 3
        )

        XCTAssertEqual(error.localizedDescription, "Camera failed to open")
        XCTAssertEqual(Set(error.channelDetails.keys), ["stacktrace"])
        XCTAssertEqual(error.channelDetails["stacktrace"], error.stackTrace)
        XCTAssertEqual(error.channelMessage, "Camera failed to open: camera disconnected")
    }

    func testCameraErrorKeepsAStackWithoutAnUnderlyingError() {
        let details = CameraError(message: "Failed to enable camera torch").channelDetails

        XCTAssertEqual(Set(details.keys), ["stacktrace"])
        XCTAssertFalse(details["stacktrace"]?.isEmpty ?? true)
    }
}

private enum PluginErrorTestCause: Error, LocalizedError {
    /// An operation was rejected.
    case rejected

    var errorDescription: String? { "camera disconnected" }
}
