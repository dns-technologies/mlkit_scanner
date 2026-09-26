import AVFoundation
import XCTest
@testable import mlkit_scanner

final class DeviceTypeExtensionTests: XCTestCase {
    func testSupportedDeviceTypesRoundTripThroughChannelCodes() {
        var values: [(AVCaptureDevice.DeviceType, Int)] = [
            (.builtInWideAngleCamera, 0),
            (.builtInTelephotoCamera, 1),
            (.builtInDualCamera, 2),
        ]
        if #available(iOS 13.0, *) {
            values += [
                (.builtInUltraWideCamera, 3),
                (.builtInDualWideCamera, 4),
                (.builtInTripleCamera, 5),
            ]
        }

        for (type, code) in values {
            XCTAssertEqual(type.code, code)
            XCTAssertEqual(AVCaptureDevice.DeviceType.fromCode(code), type)
        }
    }

    func testUnknownTypeAndCodeRemainUnsupported() {
        let unknown = AVCaptureDevice.DeviceType(rawValue: "test.unsupported")

        XCTAssertEqual(unknown.code, AVCaptureDevice.DeviceType.unsupportedCode)
        XCTAssertNil(AVCaptureDevice.DeviceType.fromCode(-1))
    }
}
