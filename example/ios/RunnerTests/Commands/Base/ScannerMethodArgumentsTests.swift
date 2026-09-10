import Foundation
import XCTest
@testable import mlkit_scanner

final class ScannerMethodArgumentsTests: XCTestCase {
    func testCodecNSNumberZeroAndOneRemainNumericValues() throws {
        let zero = NSNumber(value: Int32(0))
        let oneDouble = NSNumber(value: 1.0)

        XCTAssertEqual(
            try ScannerMethodArguments.viewId(["viewId": zero]),
            0
        )
        let options = try ScannerMethodArguments.scanOptions([
            "viewId": zero,
            "type": zero,
            "delay": NSNumber(value: Int32(150)),
        ])
        XCTAssertEqual(options.type, .barcodeRecognition)

        let zoom = try ScannerMethodArguments.zoomRatio([
            "viewId": zero,
            "value": oneDouble,
        ])
        XCTAssertEqual(zoom, 1)

        let crop = try ScannerMethodArguments.cropRect([
            "viewId": zero,
            "cropRect": [
                "scaleWidth": oneDouble,
                "scaleHeight": oneDouble,
                "offsetX": NSNumber(value: 0.0),
                "offsetY": NSNumber(value: 0.0),
            ],
        ])
        XCTAssertEqual(crop.scaleWidth, 1)
        XCTAssertEqual(crop.offsetX, 0)
    }

    func testNumericArgumentsRejectCodecBooleanValues() {
        let codecBoolean = NSNumber(value: true)

        assertInvalid {
            _ = try ScannerMethodArguments.viewId(["viewId": codecBoolean])
        }
        assertInvalid {
            _ = try ScannerMethodArguments.zoomRatio([
                "viewId": NSNumber(value: Int32(0)),
                "value": codecBoolean,
            ])
        }
    }

    func testScanOptionsRequireKnownTypeAndIntegerDelay() throws {
        let options = try ScannerMethodArguments.scanOptions([
            "viewId": 42,
            "type": 0,
            "delay": 150,
        ])

        XCTAssertEqual(options.type, .barcodeRecognition)
        XCTAssertEqual(options.delay, 150)
        assertInvalid {
            _ = try ScannerMethodArguments.scanOptions([
                "viewId": 42,
                "type": 1,
                "delay": 0,
            ])
        }
        assertInvalid {
            _ = try ScannerMethodArguments.scanOptions([
                "viewId": 42,
                "type": 0,
                "delay": 1.5,
            ])
        }
    }

    func testZoomRatioAcceptsPositiveFiniteValuesAndRejectsInvalidValues() throws {
        let value = try ScannerMethodArguments.zoomRatio([
            "viewId": 42,
            "value": 3.0,
        ])

        XCTAssertEqual(value, 3)
        for invalidValue: Any in [Double.nan, Double.infinity, -0.01, 0.0, "2.0"] {
            assertInvalid {
                _ = try ScannerMethodArguments.zoomRatio([
                    "viewId": 42,
                    "value": invalidValue,
                ])
            }
        }
    }

    func testCropUsesDefaultsAndRejectsInvalidComponents() throws {
        let value = try ScannerMethodArguments.cropRect([
            "viewId": 42,
            "cropRect": [String: Any](),
        ])

        XCTAssertEqual(value.scaleWidth, 1)
        XCTAssertEqual(value.scaleHeight, 1)
        XCTAssertEqual(value.offsetX, 0)
        XCTAssertEqual(value.offsetY, 0)

        let invalidCrops: [[String: Any]] = [
            ["scaleWidth": 0.0],
            ["scaleHeight": -1.0],
            ["offsetX": Double.nan],
            ["offsetY": -Double.infinity],
            ["scaleWidth": "0.5"],
        ]
        for crop in invalidCrops {
            assertInvalid {
                _ = try ScannerMethodArguments.cropRect([
                    "viewId": 42,
                    "cropRect": crop,
                ])
            }
        }
    }

    func testViewIdentityRejectsMissingFractionalAndNegativeValues() {
        let invalidArguments: [Any?] = [
            nil,
            [String: Any](),
            ["viewId": -1],
            ["viewId": 1.5],
            ["viewId": 1e20],
        ]

        for arguments in invalidArguments {
            assertInvalid {
                _ = try ScannerMethodArguments.viewId(arguments)
            }
        }
    }

    private func assertInvalid(
        file: StaticString = #filePath,
        line: UInt = #line,
        _ operation: () throws -> Void
    ) {
        XCTAssertThrowsError(try operation(), file: file, line: line) { error in
            XCTAssertEqual(error as? MlKitPluginError, .invalidArguments, file: file, line: line)
        }
    }
}
