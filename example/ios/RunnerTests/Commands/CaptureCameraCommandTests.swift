import Flutter
import XCTest
@testable import mlkit_scanner

final class CaptureCameraCommandTests: XCTestCase {
    func testExecuteCompletesAfterSessionCapture() {
        let session = RecordingScannerHardware()
        let command = CaptureCameraCommand(scannerDevice: session)
        var resultValue: Any? = true

        command.execute(call(viewId: 42)) { resultValue = $0 }

        XCTAssertEqual(session.captureViewId, 42)
        XCTAssertNil(resultValue)
    }

    func testExecuteReturnsCaptureFailureToFlutter() {
        let session = RecordingScannerHardware()
        session.completionError = MlKitPluginError.initCameraError
        let command = CaptureCameraCommand(scannerDevice: session)
        var resultValue: Any?

        command.execute(call(viewId: 42)) { resultValue = $0 }

        XCTAssertEqual((resultValue as? FlutterError)?.code, "1")
    }

    func testExecuteRejectsMalformedViewIdWithoutCallingSession() {
        let session = RecordingScannerHardware()
        let command = CaptureCameraCommand(scannerDevice: session)
        var resultValue: Any?

        command.execute(call(viewId: -1)) { resultValue = $0 }

        XCTAssertNil(session.captureViewId)
        XCTAssertEqual((resultValue as? FlutterError)?.code, "5")
    }

    private func call(viewId: NSNumber) -> FlutterMethodCall {
        FlutterMethodCall(methodName: "resumeCameraMethod", arguments: ["viewId": viewId, "configuration": ["zoomRatio": 1.0, "torchEnabled": false] as [String: Any]])
    }
}
