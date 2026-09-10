import Flutter
import XCTest
@testable import mlkit_scanner

final class ScannerControlCommandTests: XCTestCase {
    func testPointCommandsApplyWithoutViewOrScanIdentifiers() {
        let device = RecordingScannerHardware()
        let cases: [(ScannerCommand, String, Any?)] = [
            (SetZoomRatioCommand(scannerDevice: device), "setZoomRatio", ["value": 2.5]),
            (ToggleFlashCommand(scannerDevice: device), "toggleFlash", ["value": true]),
            (StartScanCommand(scannerDevice: device), "startScan", ["type": 0, "delay": 250]),
            (SetScanDelayCommand(scannerDevice: device), "setScanDelay", ["delay": 300]),
            (SetCropAreaCommand(scannerDevice: device), "setCropArea", ["cropRect": ["scaleWidth": 0.5]]),
            (CancelScanCommand(scannerDevice: device), "cancelScan", nil),
        ]
        for (command, method, arguments) in cases {
            var replies = 0
            command.execute(FlutterMethodCall(methodName: method, arguments: arguments)) {
                XCTAssertNil($0)
                replies += 1
            }
            XCTAssertEqual(replies, 1)
        }
        XCTAssertEqual(device.zoomRatio, 2.5)
        XCTAssertEqual(device.torchEnabled, true)
        XCTAssertEqual(device.startArguments?.delay, 250)
        XCTAssertEqual(device.scanDelay, 300)
        XCTAssertEqual(device.cropRect?.scaleWidth, 0.5)
        XCTAssertTrue(device.cancelledScan)
    }

    func testMapsPointControlFailure() {
        let device = RecordingScannerHardware()
        device.thrownError = CameraControlError(operation: .zoom, viewId: 42)
        var result: Any?
        SetZoomRatioCommand(scannerDevice: device).execute(
            FlutterMethodCall(methodName: "setZoomRatio", arguments: ["value": 2.5])
        ) { result = $0 }
        XCTAssertEqual((result as? FlutterError)?.code, "9")
        XCTAssertNil(device.zoomRatio)
    }

    func testMalformedPointArgumentsDoNotReachScanner() {
        let device = RecordingScannerHardware()
        let cases: [(ScannerCommand, Any?)] = [
            (SetZoomRatioCommand(scannerDevice: device), ["value": "bad"]),
            (ToggleFlashCommand(scannerDevice: device), ["value": 1]),
            (StartScanCommand(scannerDevice: device), ["type": 1, "delay": 0]),
            (SetScanDelayCommand(scannerDevice: device), ["delay": 0.5]),
            (SetCropAreaCommand(scannerDevice: device), ["cropRect": "bad"]),
        ]
        for (command, arguments) in cases {
            command.execute(FlutterMethodCall(methodName: "point", arguments: arguments)) {
                XCTAssertEqual(($0 as? FlutterError)?.code, MlKitPluginError.invalidArguments.rawValue)
            }
        }
        XCTAssertNil(device.zoomRatio)
        XCTAssertNil(device.torchEnabled)
        XCTAssertNil(device.startArguments)
        XCTAssertNil(device.scanDelay)
        XCTAssertNil(device.cropRect)
    }
}
