import Flutter
import XCTest
@testable import mlkit_scanner

final class ReleaseCameraCommandTests: XCTestCase {
    func testExecuteReleasesAddressedViewAndCompletes() {
        let session = RecordingScannerSession()
        let command = ReleaseCameraCommand(scannerSession: session)
        var resultValue: Any? = true

        command.execute(call(viewId: 42)) { resultValue = $0 }

        XCTAssertEqual(session.releaseViewId, 42)
        XCTAssertNil(resultValue)
    }

    private func call(viewId: NSNumber) -> FlutterMethodCall {
        FlutterMethodCall(methodName: "releaseCamera", arguments: ["viewId": viewId])
    }
}
