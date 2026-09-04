import AVFoundation
import Flutter
import XCTest
@testable import mlkit_scanner

final class SetIosCameraCommandTests: XCTestCase {
    func testExecuteUpdatesCameraForAddressedView() {
        let session = RecordingScannerSession()
        let command = SetIosCameraCommand(scannerSession: session)
        var resultValue: Any? = true

        command.execute(FlutterMethodCall(
            methodName: "setIosCamera",
            arguments: [
                "viewId": NSNumber(value: 42),
                "type": NSNumber(value: 0),
                "position": NSNumber(value: 1),
            ]
        )) { resultValue = $0 }

        XCTAssertEqual(session.cameraArguments?.viewId, 42)
        XCTAssertEqual(session.cameraArguments?.value.type, .builtInWideAngleCamera)
        XCTAssertEqual(session.cameraArguments?.value.position, .back)
        XCTAssertNil(resultValue)
    }
}
