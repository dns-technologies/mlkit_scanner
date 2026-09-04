import Flutter
import XCTest
@testable import mlkit_scanner

final class ResumeCameraCommandTests: XCTestCase {
    func testExecuteResumesAddressedViewAndCompletes() {
        let session = RecordingScannerSession()
        let command = ResumeCameraCommand(scannerSession: session)
        var resultValue: Any? = true

        command.execute(call(viewId: 42)) { resultValue = $0 }

        XCTAssertEqual(session.resumeViewId, 42)
        XCTAssertNil(resultValue)
    }

    func testExecuteReturnsResumeFailureToFlutter() {
        let session = RecordingScannerSession()
        session.completionError = MlKitPluginError.cameraSessionDisposed
        let command = ResumeCameraCommand(scannerSession: session)
        var resultValue: Any?

        command.execute(call(viewId: 42)) { resultValue = $0 }

        XCTAssertEqual((resultValue as? FlutterError)?.code, "8")
    }

    private func call(viewId: NSNumber) -> FlutterMethodCall {
        FlutterMethodCall(methodName: "resumeCamera", arguments: ["viewId": viewId])
    }
}
