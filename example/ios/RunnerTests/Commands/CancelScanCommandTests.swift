import Flutter
import XCTest
@testable import mlkit_scanner

final class CancelScanCommandTests: XCTestCase {
    func testAppliesArgumentsWithoutViewOrSubscriptionIdentifiers() throws {
        let device = try ScannerCommandFixture()
        defer { device.scanner.release() }
        try device.scanner.startScan(delay: 0)
        XCTAssertNotNil(device.analyzer.subscription)
        var replies = 0
        CancelScanCommand(scanner: device.scanner).execute(
            FlutterMethodCall(methodName: "cancelScan", arguments: nil)
        ) {
            XCTAssertNil($0)
            replies += 1
        }
        XCTAssertEqual(replies, 1)
        XCTAssertNil(device.analyzer.subscription)
        XCTAssertNil(device.camera.recognitionHandler)
    }
}
