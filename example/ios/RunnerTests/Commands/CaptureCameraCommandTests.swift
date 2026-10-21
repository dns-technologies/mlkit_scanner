import Flutter
import XCTest
@testable import mlkit_scanner

final class CaptureCameraCommandTests: XCTestCase {
    func testExecuteCompletesAfterSessionCapture() throws {
        let session = try ScannerCommandFixture(activate: false)
        defer { session.scanner.release() }
        let command = CaptureCameraCommand(scanner: session.scanner)
        var resultValue: Any? = true

        command.execute(call(viewId: 42)) { resultValue = $0 }

        XCTAssertEqual(session.camera.captureViewId, 42)
        XCTAssertNil(resultValue)
    }

    func testExecuteReturnsCaptureFailureToFlutter() throws {
        let session = try ScannerCommandFixture(activate: false)
        defer { session.scanner.release() }
        session.camera.initializationError = MlKitPluginError.initCameraError
        let command = CaptureCameraCommand(scanner: session.scanner)
        var resultValue: Any?

        command.execute(call(viewId: 42)) { resultValue = $0 }

        XCTAssertEqual((resultValue as? FlutterError)?.code, "1")
    }

    func testExecuteRejectsMalformedViewIdWithoutCallingSession() throws {
        let session = try ScannerCommandFixture(activate: false)
        defer { session.scanner.release() }
        let command = CaptureCameraCommand(scanner: session.scanner)
        var resultValue: Any?

        command.execute(call(viewId: -1)) { resultValue = $0 }

        XCTAssertNil(session.camera.captureViewId)
        XCTAssertEqual((resultValue as? FlutterError)?.code, "5")
    }

    private func call(viewId: NSNumber) -> FlutterMethodCall {
        FlutterMethodCall(methodName: "resumeCameraMethod", arguments: ["viewId": viewId, "configuration": ["zoomRatio": 1.0, "torchEnabled": false] as [String: Any]])
    }
}
