import Flutter
import XCTest
@testable import mlkit_scanner

final class CancelScanCommandTests: XCTestCase {
    func testExecuteCancelsScanningForAddressedView() {
        let session = RecordingScannerSession()
        let command = CancelScanCommand(scannerSession: session)
        var resultValue: Any? = true

        command.execute(call(viewId: 42)) { resultValue = $0 }

        XCTAssertEqual(session.cancelViewId, 42)
        XCTAssertNil(resultValue)
    }

    private func call(viewId: NSNumber) -> FlutterMethodCall {
        FlutterMethodCall(methodName: "cancelScan", arguments: ["viewId": viewId])
    }
}
