import Flutter
import XCTest
@testable import mlkit_scanner

final class SetZoomRatioCommandTests: XCTestCase {
    func testAppliesArgumentsWithoutViewOrSubscriptionIdentifiers() throws {
        let device = try ScannerCommandFixture()
        defer { device.scanner.release() }
        var replies = 0
        SetZoomRatioCommand(scanner: device.scanner).execute(
            FlutterMethodCall(methodName: "setZoomRatio", arguments: ["value": 2.5])
        ) {
            XCTAssertNil($0)
            replies += 1
        }
        XCTAssertEqual(replies, 1)
        XCTAssertEqual(device.camera.zoomRatio, 2.5)
    }

    func testMalformedArgumentsDoNotReachScanner() throws {
        let device = try ScannerCommandFixture()
        defer { device.scanner.release() }
        var result: Any?
        SetZoomRatioCommand(scanner: device.scanner).execute(
            FlutterMethodCall(methodName: "setZoomRatio", arguments: ["value": "bad"])
        ) { result = $0 }
        XCTAssertEqual((result as? FlutterError)?.code, MlKitPluginError.invalidArguments.rawValue)
        XCTAssertNil(device.camera.zoomRatio)
    }

    func testMapsPointControlFailure() throws {
        let device = try ScannerCommandFixture()
        defer { device.scanner.release() }
        device.camera.error = CameraError(message: "Failed to set camera zoom ratio to 2.5", viewId: 42)
        var result: Any?
        SetZoomRatioCommand(scanner: device.scanner).execute(
            FlutterMethodCall(methodName: "setZoomRatio", arguments: ["value": 2.5])
        ) { result = $0 }
        XCTAssertEqual((result as? FlutterError)?.code, "9")
        XCTAssertEqual((result as? FlutterError)?.message, "Failed to set camera zoom ratio to 2.5")
        XCTAssertNil(device.camera.zoomRatio)
    }
}
