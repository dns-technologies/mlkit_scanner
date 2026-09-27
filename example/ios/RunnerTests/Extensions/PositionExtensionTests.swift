import AVFoundation
import XCTest
@testable import mlkit_scanner

final class PositionExtensionTests: XCTestCase {
    func testCameraPositionsRoundTripThroughChannelCodes() {
        let values: [(AVCaptureDevice.Position, Int)] = [
            (.unspecified, 0),
            (.back, 1),
            (.front, 2),
        ]

        for (position, code) in values {
            XCTAssertEqual(position.code, code)
            XCTAssertEqual(AVCaptureDevice.Position.fromCode(code), position)
        }
    }

    func testUnknownCodeIsUnsupported() {
        XCTAssertEqual(AVCaptureDevice.Position.unsupportedCode, -1)
        XCTAssertNil(AVCaptureDevice.Position.fromCode(-1))
    }
}
