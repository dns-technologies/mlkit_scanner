import Flutter
import XCTest
@testable import mlkit_scanner

final class StartScanCommandTests: XCTestCase {
    func testAppliesArgumentsWithoutViewOrSubscriptionIdentifiers() throws {
        let device = try ScannerCommandFixture()
        defer { device.scanner.release() }
        var replies = 0
        StartScanCommand(scanner: device.scanner).execute(
            FlutterMethodCall(methodName: "startScan", arguments: ["type": 0, "delay": 250])
        ) {
            XCTAssertNil($0)
            replies += 1
        }
        XCTAssertEqual(replies, 1)
        XCTAssertEqual(device.analyzer.scanDelay, 250)
        XCTAssertNotNil(device.analyzer.subscription)
    }

    func testMalformedArgumentsDoNotReachScanner() throws {
        let device = try ScannerCommandFixture()
        defer { device.scanner.release() }
        var result: Any?
        StartScanCommand(scanner: device.scanner).execute(
            FlutterMethodCall(methodName: "startScan", arguments: ["type": 1, "delay": 0])
        ) { result = $0 }
        XCTAssertEqual((result as? FlutterError)?.code, MlKitPluginError.invalidArguments.rawValue)
        XCTAssertNil(device.analyzer.scanDelay)
    }
}
