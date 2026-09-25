import Foundation
import XCTest
@testable import mlkit_scanner

final class ScannerConfigurationTests: XCTestCase {
    func testCameraSettingsParseWithoutDartRecognitionPreferences() throws {
        let configuration = try ScannerConfiguration(arguments: validArguments)
        XCTAssertEqual(configuration.zoomRatio, 1)
        XCTAssertFalse(configuration.torchEnabled)
        XCTAssertEqual(configuration.cropRect.scaleWidth, 1)
        XCTAssertEqual(configuration.camera.position, .back)
    }

    func testRejectsMissingOrMalformedFields() {
        for key in ["zoomRatio", "torchEnabled"] {
            var arguments = validArguments
            arguments.removeValue(forKey: key)
            XCTAssertThrowsError(try ScannerConfiguration(arguments: arguments))
        }
        let invalid: [(String, Any)] = [
            ("zoomRatio", Double.nan), ("zoomRatio", "2.0"), ("zoomRatio", true),
            ("torchEnabled", NSNumber(value: 1)),
            ("cropRect", ["scaleWidth": "bad"]), ("iosCamera", "back"),
        ]
        for (key, value) in invalid {
            var arguments = validArguments
            arguments[key] = value
            XCTAssertThrowsError(try ScannerConfiguration(arguments: arguments))
        }
    }

    func testDecodingLeavesApplicationRangesToDart() throws {
        var arguments = validArguments
        arguments["zoomRatio"] = 0.0
        arguments["cropRect"] = ["scaleWidth": -1.0]
        let decoded = try ScannerConfiguration(arguments: arguments)
        XCTAssertEqual(decoded.zoomRatio, 0)
        XCTAssertEqual(decoded.cropRect.scaleWidth, -1)
    }

    private var validArguments: [String: Any] {
        ["zoomRatio": 1.0, "torchEnabled": false]
    }
}
