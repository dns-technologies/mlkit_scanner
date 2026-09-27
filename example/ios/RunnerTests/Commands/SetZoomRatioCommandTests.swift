import Flutter
import XCTest
@testable import mlkit_scanner

final class SetZoomRatioCommandTests: XCTestCase {
    func testAppliesArgumentsWithoutViewOrSubscriptionIdentifiers() {
        let device = RecordingScannerHardware()
        var replies = 0
        SetZoomRatioCommand(scannerDevice: device).execute(
            FlutterMethodCall(methodName: "setZoomRatio", arguments: ["value": 2.5])
        ) {
            XCTAssertNil($0)
            replies += 1
        }
        XCTAssertEqual(replies, 1)
        XCTAssertEqual(device.zoomRatio, 2.5)
    }

    func testMalformedArgumentsDoNotReachScanner() {
        let device = RecordingScannerHardware()
        var result: Any?
        SetZoomRatioCommand(scannerDevice: device).execute(
            FlutterMethodCall(methodName: "setZoomRatio", arguments: ["value": "bad"])
        ) { result = $0 }
        XCTAssertEqual((result as? FlutterError)?.code, MlKitPluginError.invalidArguments.rawValue)
        XCTAssertNil(device.zoomRatio)
    }

    func testMapsPointControlFailure() {
        let device = RecordingScannerHardware()
        device.thrownError = CameraControlError(operation: .zoom, viewId: 42)
        var result: Any?
        SetZoomRatioCommand(scannerDevice: device).execute(
            FlutterMethodCall(methodName: "setZoomRatio", arguments: ["value": 2.5])
        ) { result = $0 }
        XCTAssertEqual((result as? FlutterError)?.code, "9")
        XCTAssertNil(device.zoomRatio)
    }
}
