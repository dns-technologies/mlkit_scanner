import Flutter
import XCTest
@testable import mlkit_scanner

final class SetScanDelayCommandTests: XCTestCase {
    func testAppliesArgumentsWithoutViewOrSubscriptionIdentifiers() {
        let device = RecordingScannerHardware()
        var replies = 0
        SetScanDelayCommand(scannerDevice: device).execute(
            FlutterMethodCall(methodName: "setScanDelay", arguments: ["delay": 300])
        ) {
            XCTAssertNil($0)
            replies += 1
        }
        XCTAssertEqual(replies, 1)
        XCTAssertEqual(device.scanDelay, 300)
    }

    func testMalformedArgumentsDoNotReachScanner() {
        let device = RecordingScannerHardware()
        var result: Any?
        SetScanDelayCommand(scannerDevice: device).execute(
            FlutterMethodCall(methodName: "setScanDelay", arguments: ["delay": 0.5])
        ) { result = $0 }
        XCTAssertEqual((result as? FlutterError)?.code, MlKitPluginError.invalidArguments.rawValue)
        XCTAssertNil(device.scanDelay)
    }
}
