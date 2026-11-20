import Flutter
import XCTest
@testable import mlkit_scanner

final class SetScanDelayCommandTests: XCTestCase {
    func testAppliesArgumentsWithoutViewOrSubscriptionIdentifiers() throws {
        let device = try ScannerCommandFixture()
        defer { device.scanner.release() }
        try device.scanner.startScan(delay: 100)
        var replies = 0
        SetScanDelayCommand(scanner: device.scanner).execute(
            FlutterMethodCall(methodName: "setScanDelay", arguments: ["delay": 300])
        ) {
            XCTAssertNil($0)
            replies += 1
        }
        XCTAssertEqual(replies, 1)
        XCTAssertEqual(device.analyzer.scanDelay, 300)
    }

    func testMalformedArgumentsDoNotReachScanner() throws {
        let device = try ScannerCommandFixture()
        defer { device.scanner.release() }
        try device.scanner.startScan(delay: 100)
        var result: Any?
        SetScanDelayCommand(scanner: device.scanner).execute(
            FlutterMethodCall(methodName: "setScanDelay", arguments: ["delay": 0.5])
        ) { result = $0 }
        XCTAssertEqual((result as? FlutterError)?.code, MlKitPluginError.invalidArguments.rawValue)
        XCTAssertEqual(device.analyzer.scanDelay, 100)
    }
}
