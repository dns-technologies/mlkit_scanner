import AVFoundation
import XCTest
@testable import mlkit_scanner

final class CameraUtilTests: XCTestCase {
    func testAvailableCamerasAreVideoDevicesFromSupportedDiscoveryTypes() {
        var supportedTypes: Set<AVCaptureDevice.DeviceType> = [
            .builtInWideAngleCamera,
            .builtInTelephotoCamera,
            .builtInDualCamera,
        ]
        if #available(iOS 13.0, *) {
            supportedTypes.formUnion([
                .builtInUltraWideCamera,
                .builtInDualWideCamera,
                .builtInTripleCamera,
            ])
        }

        let cameras = CameraUtil().getAvailableCameras()

        XCTAssertTrue(cameras.allSatisfy { $0.hasMediaType(.video) })
        XCTAssertTrue(cameras.allSatisfy { supportedTypes.contains($0.deviceType) })
    }
}
