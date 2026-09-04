import Flutter
import XCTest
@testable import mlkit_scanner

final class CaptureCameraCommandTests: XCTestCase {
    func testExecuteCompletesAfterSessionCapture() {
        let session = RecordingScannerSession()
        let command = CaptureCameraCommand(scannerSession: session)
        var resultValue: Any? = true

        command.execute(call(viewId: 42)) { resultValue = $0 }

        XCTAssertEqual(session.captureViewId, 42)
        XCTAssertNil(resultValue)
    }

    func testExecuteReturnsCaptureFailureToFlutter() {
        let session = RecordingScannerSession()
        session.completionError = MlKitPluginError.initCameraError
        let command = CaptureCameraCommand(scannerSession: session)
        var resultValue: Any?

        command.execute(call(viewId: 42)) { resultValue = $0 }

        XCTAssertEqual((resultValue as? FlutterError)?.code, "1")
    }

    func testExecuteRejectsMalformedViewIdWithoutCallingSession() {
        let session = RecordingScannerSession()
        let command = CaptureCameraCommand(scannerSession: session)
        var resultValue: Any?

        command.execute(call(viewId: -1)) { resultValue = $0 }

        XCTAssertNil(session.captureViewId)
        XCTAssertEqual((resultValue as? FlutterError)?.code, "5")
    }

    private func call(viewId: NSNumber) -> FlutterMethodCall {
        FlutterMethodCall(methodName: "captureCamera", arguments: ["viewId": viewId])
    }
}
