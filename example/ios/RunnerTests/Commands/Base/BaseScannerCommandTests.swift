import Flutter
import XCTest
@testable import mlkit_scanner

final class BaseScannerCommandTests: XCTestCase {
    func testSuccessCompletesFlutterResultWithNil() {
        let command = BaseScannerCommand(scannerDevice: RecordingScannerHardware())
        var invocationCount = 0
        var channelValue: Any? = true

        command.success {
            invocationCount += 1
            channelValue = $0
        }

        XCTAssertEqual(invocationCount, 1)
        XCTAssertNil(channelValue)
    }

    func testCompleteMapsOptionalErrorToSuccessOrFailure() {
        let command = BaseScannerCommand(scannerDevice: RecordingScannerHardware())
        var successValue: Any? = true
        var failureValue: Any?

        command.complete({ successValue = $0 }, error: nil)
        command.complete(
            { failureValue = $0 },
            error: MlKitPluginError.cameraSessionDisposed
        )

        XCTAssertNil(successValue)
        let flutterError = failureValue as? FlutterError
        XCTAssertEqual(flutterError?.code, "8")
        XCTAssertEqual(flutterError?.message, "Camera session has been disposed")
    }

    func testCameraControlFailureUsesStableCodeAndStructuredDetails() {
        let command = BaseScannerCommand(scannerDevice: RecordingScannerHardware())
        let error = CameraControlError(
            operation: .zoom,
            viewId: 42,
            underlyingError: BaseScannerCommandTestError.rejected
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

    func testUnknownFailureKeepsItsLocalizedDescription() {
        let command = BaseScannerCommand(scannerDevice: RecordingScannerHardware())
        var channelValue: Any?

        command.reportError(
            { channelValue = $0 },
            error: BaseScannerCommandTestError.rejected
        )

        let flutterError = channelValue as? FlutterError
        XCTAssertEqual(flutterError?.code, MlKitPluginError.unknownError.rawValue)
        XCTAssertEqual(
            flutterError?.message,
            BaseScannerCommandTestError.rejected.localizedDescription
        )
    }
}

private enum BaseScannerCommandTestError: Error {
    case rejected
}

final class RecordingScannerHardware: ScannerDevice {
    var captureViewId: Int64?
    var releasedCamera = false
    var torchEnabled: Bool?
    var startArguments: (type: RecognitionType, delay: Int)?
    var cancelledScan = false
    var scanDelay: Int?
    var zoomRatio: Double?
    var cropRect: CropRect?
    var completionError: Error?
    var thrownError: Error?
    var releaseCount = 0


    func captureCamera(viewId: Int64, configuration: ScannerConfiguration, completion: @escaping ScannerCompletion) {
        captureViewId = viewId
        completion(completionError)
    }

    func releaseCamera( completion: @escaping () -> Void) {
        releasedCamera = true
        completion()
    }

    func setTorch(enabled: Bool) throws {
        try throwConfiguredError()
        torchEnabled = enabled
    }

    func setZoomRatio(value: Double) throws {
        try throwConfiguredError()
        zoomRatio = value
    }

    func setCropArea(cropRect: CropRect) throws {
        try throwConfiguredError()
        self.cropRect = cropRect
    }

    func updateScanPeriod(delay: Int) throws {
        try throwConfiguredError()
        scanDelay = delay
    }

    func startScan(type: RecognitionType, delay: Int) throws {
        try throwConfiguredError()
        startArguments = (type, delay)
    }

    func cancelScan() throws {
        try throwConfiguredError()
        cancelledScan = true
    }


    func release() {
        releaseCount += 1
    }

    private func throwConfiguredError() throws {
        if let thrownError = thrownError {
            throw thrownError
        }
    }
}
