import Flutter
import XCTest
@testable import mlkit_scanner

final class BaseScannerCommandTests: XCTestCase {
    func testSuccessCompletesFlutterResultWithNil() {
        let command = BaseScannerCommand(scannerSession: RecordingScannerSession())
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
        let command = BaseScannerCommand(scannerSession: RecordingScannerSession())
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
        let command = BaseScannerCommand(scannerSession: RecordingScannerSession())
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
        let command = BaseScannerCommand(scannerSession: RecordingScannerSession())
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

final class RecordingScannerSession: ScannerSession {
    var captureViewId: Int64?
    var releaseViewId: Int64?
    var pauseViewId: Int64?
    var resumeViewId: Int64?
    var toggleViewId: Int64?
    var startArguments: (viewId: Int64, type: RecognitionType, delay: Int)?
    var cancelViewId: Int64?
    var delayArguments: (viewId: Int64, delay: Int)?
    var zoomArguments: (viewId: Int64, value: Double)?
    var cropArguments: (viewId: Int64, value: CropRect)?
    var cameraArguments: (viewId: Int64, value: CameraData)?
    var completionError: Error?
    var thrownError: Error?
    var releaseCount = 0

    func createView(
        frame: CGRect,
        viewId: Int64,
        registration: ScannerViewRegistration
    ) -> CameraPreview {
        fatalError("createView is not used by command tests")
    }

    func captureCamera(viewId: Int64, completion: @escaping ScannerSessionCompletion) {
        captureViewId = viewId
        completion(completionError)
    }

    func releaseCamera(viewId: Int64, completion: @escaping () -> Void) {
        releaseViewId = viewId
        completion()
    }

    func pauseCamera(viewId: Int64, completion: @escaping () -> Void) {
        pauseViewId = viewId
        completion()
    }

    func resumeCamera(viewId: Int64, completion: @escaping ScannerSessionCompletion) {
        resumeViewId = viewId
        completion(completionError)
    }

    func toggleFlash(viewId: Int64) throws {
        try throwConfiguredError()
        toggleViewId = viewId
    }

    func startScan(viewId: Int64, type: RecognitionType, delay: Int) throws {
        try throwConfiguredError()
        startArguments = (viewId, type, delay)
    }

    func cancelScan(viewId: Int64) {
        cancelViewId = viewId
    }

    func updateScanPeriod(viewId: Int64, delay: Int) throws {
        try throwConfiguredError()
        delayArguments = (viewId, delay)
    }

    func setZoomRatio(viewId: Int64, value: Double) throws {
        try throwConfiguredError()
        zoomArguments = (viewId, value)
    }

    func setCropArea(viewId: Int64, cropRect: CropRect) throws {
        try throwConfiguredError()
        cropArguments = (viewId, cropRect)
    }

    func setCamera(viewId: Int64, camera: CameraData) throws {
        try throwConfiguredError()
        cameraArguments = (viewId, camera)
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
