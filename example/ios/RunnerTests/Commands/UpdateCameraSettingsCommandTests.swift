import Flutter
import XCTest
@testable import mlkit_scanner

final class UpdateCameraSettingsCommandTests: XCTestCase {
    func testAppliesAllSuppliedSettingsWithOneReply() {
        let device = RecordingScannerHardware()
        var replies = 0
        UpdateCameraSettingsCommand(scannerDevice: device).execute(
            FlutterMethodCall(methodName: "updateCameraSettings", arguments: [
                "zoomRatio": 2.5, "torchEnabled": true, "cropRect": ["scaleWidth": 0.5],
            ])
        ) {
            XCTAssertNil($0)
            replies += 1
        }
        XCTAssertEqual(replies, 1)
        XCTAssertEqual(device.zoomRatio, 2.5)
        XCTAssertEqual(device.torchEnabled, true)
        XCTAssertEqual(device.cropRect?.scaleWidth, 0.5)
    }

    func testOmittedFieldsAreUntouchedAndFalseTorchIsApplied() {
        let device = RecordingScannerHardware()
        UpdateCameraSettingsCommand(scannerDevice: device).execute(
            FlutterMethodCall(methodName: "updateCameraSettings", arguments: ["torchEnabled": false])
        ) { XCTAssertNil($0) }
        XCTAssertEqual(device.torchEnabled, false)
        XCTAssertNil(device.zoomRatio)
        XCTAssertNil(device.cropRect)
    }

    func testMalformedBatchDoesNotApplyAnySettings() {
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
            let device = RecordingScannerHardware()
            var replies = 0
            UpdateCameraSettingsCommand(scannerDevice: device).execute(
                FlutterMethodCall(methodName: "updateCameraSettings", arguments: arguments)
            ) {
                XCTAssertEqual(($0 as? FlutterError)?.code, MlKitPluginError.invalidArguments.rawValue)
                replies += 1
            }
            XCTAssertEqual(replies, 1)
            XCTAssertNil(device.zoomRatio)
            XCTAssertNil(device.torchEnabled)
            XCTAssertNil(device.cropRect)
        }
    }

    func testControlFailureStopsTheBatchAndPreservesTypedError() {
        let device = RecordingScannerHardware()
        device.thrownError = CameraControlError(operation: .zoom, viewId: 42)
        var replies = 0
        UpdateCameraSettingsCommand(scannerDevice: device).execute(
            FlutterMethodCall(methodName: "updateCameraSettings", arguments: ["zoomRatio": 2.0, "torchEnabled": true])
        ) {
            XCTAssertEqual(($0 as? FlutterError)?.code, "9")
            replies += 1
        }
        XCTAssertEqual(replies, 1)
        XCTAssertNil(device.zoomRatio)
        XCTAssertNil(device.torchEnabled)
    }
}
