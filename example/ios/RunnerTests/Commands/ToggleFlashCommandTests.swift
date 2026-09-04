import Flutter
import XCTest
@testable import mlkit_scanner

final class ToggleFlashCommandTests: XCTestCase {
    func testExecuteTogglesTorchForAddressedView() {
        let session = RecordingScannerSession()
        let command = ToggleFlashCommand(scannerSession: session)
        var resultValue: Any? = true

        command.execute(FlutterMethodCall(
            methodName: "toggleFlash",
            arguments: ["viewId": NSNumber(value: 42)]
        )) { resultValue = $0 }

        XCTAssertEqual(session.toggleViewId, 42)
        XCTAssertNil(resultValue)
    }
}
