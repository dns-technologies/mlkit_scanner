import Flutter
import XCTest
@testable import mlkit_scanner

final class SetScanDelayCommandTests: XCTestCase {
    func testExecuteUpdatesDelayForAddressedView() {
        let session = RecordingScannerSession()
        let command = SetScanDelayCommand(scannerSession: session)
        var resultValue: Any? = true

        command.execute(FlutterMethodCall(
            methodName: "setScanDelay",
            arguments: ["viewId": NSNumber(value: 42), "delay": NSNumber(value: 250)]
        )) { resultValue = $0 }

        XCTAssertEqual(session.delayArguments?.viewId, 42)
        XCTAssertEqual(session.delayArguments?.delay, 250)
        XCTAssertNil(resultValue)
    }
}
