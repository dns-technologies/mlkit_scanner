import Flutter
import XCTest
@testable import mlkit_scanner

final class UpdateCameraSettingsCommandTests: XCTestCase {
    func testAppliesAllSuppliedSettingsWithOneReply() throws {
        let device = try ScannerCommandFixture()
        defer { device.scanner.release() }
        var replies = 0
        UpdateCameraSettingsCommand(scanner: device.scanner).execute(
            FlutterMethodCall(methodName: "updateCameraSettings", arguments: [
                "zoomRatio": 2.5, "torchEnabled": true, "cropRect": ["scaleWidth": 0.5],
            ])
        ) {
            XCTAssertNil($0)
            replies += 1
        }
        XCTAssertEqual(replies, 1)
        XCTAssertEqual(device.camera.zoomRatio, 2.5)
        XCTAssertEqual(device.camera.torchEnabled, true)
        XCTAssertEqual(device.camera.cropRect?.scaleWidth, 0.5)
    }

    func testOmittedFieldsAreUntouchedAndFalseTorchIsApplied() throws {
        let device = try ScannerCommandFixture()
        defer { device.scanner.release() }
        UpdateCameraSettingsCommand(scanner: device.scanner).execute(
            FlutterMethodCall(methodName: "updateCameraSettings", arguments: ["torchEnabled": false])
        ) { XCTAssertNil($0) }
        XCTAssertEqual(device.camera.torchEnabled, false)
        XCTAssertNil(device.camera.zoomRatio)
        XCTAssertNil(device.camera.cropRect)
    }

    func testMalformedBatchDoesNotApplyAnySettings() throws {
        let invalid: [Any?] = [
            nil,
            ["zoomRatio": 2.0, "torchEnabled": 1],
            ["zoomRatio": 2.0, "cropRect": "bad"],
            ["zoomRatio": "2.0"],
            ["zoomRatio": Double.nan],
            ["zoomRatio": true],
            ["torchEnabled": NSNull()],
            ["cropRect": NSNull()],
        ]
        for arguments in invalid {
            let device = try ScannerCommandFixture()
            defer { device.scanner.release() }
            var replies = 0
            UpdateCameraSettingsCommand(scanner: device.scanner).execute(
                FlutterMethodCall(methodName: "updateCameraSettings", arguments: arguments)
            ) {
                XCTAssertEqual(($0 as? FlutterError)?.code, MlKitPluginError.invalidArguments.rawValue)
                replies += 1
            }
            XCTAssertEqual(replies, 1)
            XCTAssertNil(device.camera.zoomRatio)
            XCTAssertNil(device.camera.torchEnabled)
            XCTAssertNil(device.camera.cropRect)
        }
    }

    func testControlFailureStopsTheBatchAndPreservesTypedError() throws {
        let device = try ScannerCommandFixture()
        defer { device.scanner.release() }
        device.camera.error = CameraError(message: "Failed to set camera zoom ratio to 2.0", viewId: 42)
        var replies = 0
        UpdateCameraSettingsCommand(scanner: device.scanner).execute(
            FlutterMethodCall(methodName: "updateCameraSettings", arguments: ["zoomRatio": 2.0, "torchEnabled": true])
        ) {
            XCTAssertEqual(($0 as? FlutterError)?.code, "9")
            XCTAssertEqual(($0 as? FlutterError)?.message, "Failed to set camera zoom ratio to 2.0")
            replies += 1
        }
        XCTAssertEqual(replies, 1)
        XCTAssertNil(device.camera.zoomRatio)
        XCTAssertNil(device.camera.torchEnabled)
    }
}
