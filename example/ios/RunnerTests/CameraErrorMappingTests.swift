import Flutter
import XCTest
@testable import mlkit_scanner

final class CameraErrorMappingTests: XCTestCase {
    func testCameraControlFailureUsesStableCodeAndStructuredDetails() {
        let command = BaseScannerCommand(scannerSession: makeSession())
        let error = CameraControlError(
            operation: .zoom,
            viewId: 42,
            underlyingError: MappingTestError.rejected
        )
        var channelValue: Any?

        command.reportError({ channelValue = $0 }, error: error)

        let flutterError = channelValue as? FlutterError
        XCTAssertEqual(flutterError?.code, "9")
        XCTAssertEqual(flutterError?.message, "Camera control operation failed")
        let details = flutterError?.details as? [String: Any]
        XCTAssertEqual(details?["operation"] as? String, "zoom")
        XCTAssertEqual(details?["viewId"] as? Int64, 42)
        XCTAssertNotNil(details?["cause"])
    }

    func testPluginErrorsKeepTheirStableCodes() {
        let command = BaseScannerCommand(scannerSession: makeSession())
        var channelValue: Any?

        command.reportError(
            { channelValue = $0 },
            error: MlKitPluginError.cameraSessionDisposed
        )

        let flutterError = channelValue as? FlutterError
        XCTAssertEqual(flutterError?.code, "8")
        XCTAssertEqual(flutterError?.message, "Camera session has been disposed")
    }

    private func makeSession() -> ScannerSessionImpl {
        ScannerSessionImpl(
            onScanResult: { _, _ in },
            onTorchChanged: { _, _ in },
            notificationCenter: NotificationCenter(),
            hostIsActive: { true }
        )
    }
}

private enum MappingTestError: Error {
    case rejected
}
