import Flutter
import XCTest
@testable import mlkit_scanner

final class StartScanCommandTests: XCTestCase {
    func testExecuteStartsRecognitionForAddressedView() {
        let session = RecordingScannerSession()
        let command = StartScanCommand(scannerSession: session)
        var resultValue: Any? = true

        command.execute(FlutterMethodCall(
            methodName: "startScan",
            arguments: [
                "viewId": NSNumber(value: 42),
                "type": NSNumber(value: 0),
                "delay": NSNumber(value: 250),
            ]
        )) { resultValue = $0 }

        XCTAssertEqual(session.startArguments?.viewId, 42)
        XCTAssertEqual(session.startArguments?.type, .barcodeRecognition)
        XCTAssertEqual(session.startArguments?.delay, 250)
        XCTAssertNil(resultValue)
    }
}
