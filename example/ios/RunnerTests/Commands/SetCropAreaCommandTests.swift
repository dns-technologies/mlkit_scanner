import Flutter
import XCTest
@testable import mlkit_scanner

final class SetCropAreaCommandTests: XCTestCase {
    func testAppliesArgumentsWithoutViewOrSubscriptionIdentifiers() {
        let device = RecordingScannerHardware()
        var replies = 0
        SetCropAreaCommand(scannerDevice: device).execute(
            FlutterMethodCall(methodName: "setCropAreaMethod", arguments: ["cropRect": ["scaleWidth": 0.5]])
        ) {
            XCTAssertNil($0)
            replies += 1
        }
        XCTAssertEqual(replies, 1)
        XCTAssertEqual(device.cropRect?.scaleWidth, 0.5)
    }

    func testMalformedArgumentsDoNotReachScanner() {
        let device = RecordingScannerHardware()
        var result: Any?
        SetCropAreaCommand(scannerDevice: device).execute(
            FlutterMethodCall(methodName: "setCropAreaMethod", arguments: ["cropRect": "bad"])
        ) { result = $0 }
        XCTAssertEqual((result as? FlutterError)?.code, MlKitPluginError.invalidArguments.rawValue)
        XCTAssertNil(device.cropRect?.scaleWidth)
    }
}
