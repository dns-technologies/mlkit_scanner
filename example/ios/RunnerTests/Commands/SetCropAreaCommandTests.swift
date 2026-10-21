import Flutter
import XCTest
@testable import mlkit_scanner

final class SetCropAreaCommandTests: XCTestCase {
    func testAppliesArgumentsWithoutViewOrSubscriptionIdentifiers() throws {
        let device = try ScannerCommandFixture()
        defer { device.scanner.release() }
        var replies = 0
        SetCropAreaCommand(scanner: device.scanner).execute(
            FlutterMethodCall(methodName: "setCropAreaMethod", arguments: ["cropRect": ["scaleWidth": 0.5]])
        ) {
            XCTAssertNil($0)
            replies += 1
        }
        XCTAssertEqual(replies, 1)
        XCTAssertEqual(device.camera.cropRect?.scaleWidth, 0.5)
    }

    func testMalformedArgumentsDoNotReachScanner() throws {
        let device = try ScannerCommandFixture()
        defer { device.scanner.release() }
        var result: Any?
        SetCropAreaCommand(scanner: device.scanner).execute(
            FlutterMethodCall(methodName: "setCropAreaMethod", arguments: ["cropRect": "bad"])
        ) { result = $0 }
        XCTAssertEqual((result as? FlutterError)?.code, MlKitPluginError.invalidArguments.rawValue)
        XCTAssertNil(device.camera.cropRect?.scaleWidth)
    }
}
