import Flutter
import XCTest
@testable import mlkit_scanner

final class GetIosAvailableCamerasCommandTests: XCTestCase {
    func testExecuteReturnsOnlyJsonCompatibleCameraDescriptors() {
        let command = GetIosAvailableCamerasCommand(
            scannerSession: RecordingScannerSession()
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
