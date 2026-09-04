import Flutter
import XCTest
@testable import mlkit_scanner

final class PauseCameraCommandTests: XCTestCase {
    func testExecutePausesAddressedViewAndCompletes() {
        let session = RecordingScannerSession()
        let command = PauseCameraCommand(scannerSession: session)
        var resultValue: Any? = true

        command.execute(call(viewId: 42)) { resultValue = $0 }

        XCTAssertEqual(session.pauseViewId, 42)
        XCTAssertNil(resultValue)
    }

    private func call(viewId: NSNumber) -> FlutterMethodCall {
        FlutterMethodCall(methodName: "pauseCamera", arguments: ["viewId": viewId])
    }
}
