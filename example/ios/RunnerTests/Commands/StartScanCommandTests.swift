import Flutter
import XCTest
@testable import mlkit_scanner

final class StartScanCommandTests: XCTestCase {
    func testAppliesArgumentsWithoutViewOrSubscriptionIdentifiers() {
        let device = RecordingScannerHardware()
        var replies = 0
        StartScanCommand(scannerDevice: device).execute(
            FlutterMethodCall(methodName: "startScan", arguments: ["type": 0, "delay": 250])
        ) {
            XCTAssertNil($0)
            replies += 1
        }
        XCTAssertEqual(replies, 1)
        XCTAssertEqual(device.startArguments?.delay, 250)
    }

    func testMalformedArgumentsDoNotReachScanner() {
        let device = RecordingScannerHardware()
        var result: Any?
        StartScanCommand(scannerDevice: device).execute(
            FlutterMethodCall(methodName: "startScan", arguments: ["type": 1, "delay": 0])
        ) { result = $0 }
        XCTAssertEqual((result as? FlutterError)?.code, MlKitPluginError.invalidArguments.rawValue)
        XCTAssertNil(device.startArguments?.delay)
    }
}
