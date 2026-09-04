import Flutter
import XCTest
@testable import mlkit_scanner

final class SetCropAreaCommandTests: XCTestCase {
    func testExecuteUpdatesCropForAddressedView() {
        let session = RecordingScannerSession()
        let command = SetCropAreaCommand(scannerSession: session)
        var resultValue: Any? = true

        command.execute(FlutterMethodCall(
            methodName: "setCropArea",
            arguments: [
                "viewId": NSNumber(value: 42),
                "cropRect": [
                    "scaleWidth": NSNumber(value: 0.5),
                    "scaleHeight": NSNumber(value: 0.75),
                    "offsetX": NSNumber(value: 0.1),
                    "offsetY": NSNumber(value: -0.2),
                ],
            ]
        )) { resultValue = $0 }

        XCTAssertEqual(session.cropArguments?.viewId, 42)
        XCTAssertEqual(session.cropArguments?.value.scaleWidth, 0.5)
        XCTAssertEqual(session.cropArguments?.value.scaleHeight, 0.75)
        XCTAssertEqual(session.cropArguments?.value.offsetX, 0.1)
        XCTAssertEqual(session.cropArguments?.value.offsetY, -0.2)
        XCTAssertNil(resultValue)
    }
}
