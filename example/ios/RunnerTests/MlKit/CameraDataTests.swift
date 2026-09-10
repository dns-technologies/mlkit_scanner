import AVFoundation
import XCTest
@testable import mlkit_scanner

final class CameraDataTests: XCTestCase {
    func testCodecArgumentsDecodeAndRoundTrip() throws {
        let camera = try CameraData(arguments: [
            "type": NSNumber(value: Int32(0)),
            "position": NSNumber(value: Int32(1)),
        ])

        XCTAssertEqual(camera.type, .builtInWideAngleCamera)
        XCTAssertEqual(camera.position, .back)
        XCTAssertEqual(camera.toJson()["type"] as? Int, 0)
        XCTAssertEqual(camera.toJson()["position"] as? Int, 1)
    }

    func testNativeInitializerPreservesTypeAndPosition() {
        let camera = CameraData(type: .builtInTelephotoCamera, position: .front)

        XCTAssertEqual(camera.type, .builtInTelephotoCamera)
        XCTAssertEqual(camera.position, .front)
    }

    func testInvalidCodesFractionsAndBooleansAreRejected() {
        let invalidValues: [[String: Any]] = [
            ["type": -1, "position": 1],
            ["type": 0, "position": -1],
            ["type": 0.5, "position": 1],
            ["type": NSNumber(value: true), "position": 1],
        ]

        for value in invalidValues {
            XCTAssertThrowsError(try CameraData(arguments: value)) {
                XCTAssertEqual($0 as? MlKitPluginError, .invalidArguments)
            }
        }
    }
}
