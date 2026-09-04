import Flutter
import XCTest
@testable import mlkit_scanner

final class SetZoomRatioCommandTests: XCTestCase {
    func testExecuteUpdatesZoomForAddressedView() {
        let session = RecordingScannerSession()
        let command = SetZoomRatioCommand(scannerSession: session)
        var resultValue: Any? = true

        command.execute(FlutterMethodCall(
            methodName: "setZoomRatio",
            arguments: ["viewId": NSNumber(value: 42), "value": NSNumber(value: 2.5)]
        )) { resultValue = $0 }

        XCTAssertEqual(session.zoomArguments?.viewId, 42)
        XCTAssertEqual(session.zoomArguments?.value, 2.5)
        XCTAssertNil(resultValue)
    }

    func testExecuteMapsSessionControlFailure() {
        let session = RecordingScannerSession()
        session.thrownError = CameraControlError(operation: .zoom, viewId: 42)
        let command = SetZoomRatioCommand(scannerSession: session)
        var resultValue: Any?

        command.execute(FlutterMethodCall(
            methodName: "setZoomRatio",
            arguments: ["viewId": NSNumber(value: 42), "value": NSNumber(value: 2.5)]
        )) { resultValue = $0 }

        XCTAssertEqual((resultValue as? FlutterError)?.code, "9")
    }
}
