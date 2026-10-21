import Flutter
import XCTest
@testable import mlkit_scanner

final class GetIosAvailableCamerasCommandTests: XCTestCase {
    func testExecuteReturnsOnlyJsonCompatibleCameraDescriptors() throws {
        let fixture = try ScannerCommandFixture(activate: false)
        defer { fixture.scanner.release() }
        let command = GetIosAvailableCamerasCommand(
            scanner: fixture.scanner
        )
        var resultValue: Any?

        command.execute(FlutterMethodCall(
            methodName: "getIosAvailableCameras",
            arguments: nil
        )) { resultValue = $0 }

        let cameras = resultValue as? [[String: Any]]
        XCTAssertNotNil(cameras)
        for camera in cameras ?? [] {
            XCTAssertNotNil(camera["type"] as? Int)
            XCTAssertNotNil(camera["position"] as? Int)
        }
    }
}
