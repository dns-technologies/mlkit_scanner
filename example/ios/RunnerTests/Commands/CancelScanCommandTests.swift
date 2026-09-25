import Flutter
import XCTest
@testable import mlkit_scanner

final class CancelScanCommandTests: XCTestCase {
    func testAppliesArgumentsWithoutViewOrSubscriptionIdentifiers() {
        let device = RecordingScannerHardware()
        var replies = 0
        CancelScanCommand(scannerDevice: device).execute(
            FlutterMethodCall(methodName: "cancelScan", arguments: nil)
        ) {
            XCTAssertNil($0)
            replies += 1
        }
        XCTAssertEqual(replies, 1)
        XCTAssertEqual(device.cancelledScan, true)
    }
}
