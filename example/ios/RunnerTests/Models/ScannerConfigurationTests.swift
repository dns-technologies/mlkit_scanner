import Foundation
import XCTest
@testable import mlkit_scanner

final class ScannerConfigurationTests: XCTestCase {
    func testCompleteDartSnapshotUsesCrossPlatformDefaults() throws {
        let configuration = try ScannerConfiguration(arguments: validArguments)
        XCTAssertEqual(configuration.zoomRatio, 1)
        XCTAssertFalse(configuration.torchEnabled)
        XCTAssertFalse(configuration.scanEnabled)
        XCTAssertEqual(configuration.scanDelay, 0)
        XCTAssertEqual(configuration.cropRect.scaleWidth, 1)
        XCTAssertEqual(configuration.camera.position, .back)
    }

    func testRejectsMissingOrMalformedFields() {
        for key in ["zoomRatio", "torchEnabled", "scanEnabled", "scanDelay"] {
            var arguments = validArguments
            arguments.removeValue(forKey: key)
            XCTAssertThrowsError(try ScannerConfiguration(arguments: arguments))
        }
        let invalid: [(String, Any)] = [
            ("zoomRatio", Double.nan), ("zoomRatio", 0), ("zoomRatio", true),
            ("scanDelay", 1.5), ("scanDelay", true),
            ("torchEnabled", NSNumber(value: 1)), ("scanEnabled", "true"),
            ("cropRect", ["scaleWidth": 0]), ("iosCamera", "back"),
        ]
        for (key, value) in invalid {
            var arguments = validArguments
            arguments[key] = value
            XCTAssertThrowsError(try ScannerConfiguration(arguments: arguments))
        }
    }

    private var validArguments: [String: Any] {
        ["zoomRatio": 1.0, "torchEnabled": false, "scanEnabled": false, "scanDelay": 0]
    }
}
