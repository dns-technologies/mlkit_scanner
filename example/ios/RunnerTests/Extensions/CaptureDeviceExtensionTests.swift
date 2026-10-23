import AVFoundation
import XCTest
@testable import mlkit_scanner

final class CaptureDeviceExtensionTests: XCTestCase {
    func testCameraDataReflectsDiscoveredDeviceIdentity() throws {
        guard let camera = CameraUtil().getAvailableCameras().first else {
            throw XCTSkip("The simulator exposes no capture device")
        }

        let data = camera.toCameraData()

        XCTAssertEqual(data.type, camera.deviceType)
        XCTAssertEqual(data.position, camera.position)
    }

    func testSupportedFlagMatchesPublishedDeviceRequirements() throws {
        guard let camera = CameraUtil().getAvailableCameras().first else {
            throw XCTSkip("The simulator exposes no capture device")
        }

        XCTAssertEqual(
            camera.isSupported,
            camera.isFocusPointOfInterestSupported
                && camera.hasTorch
                && camera.position.code != AVCaptureDevice.Position.unsupportedCode
                && camera.deviceType.code != AVCaptureDevice.DeviceType.unsupportedCode
        )
    }
}
