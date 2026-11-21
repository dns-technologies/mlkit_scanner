import XCTest
@testable import mlkit_scanner

final class CropRectTests: XCTestCase {
    func testDefaultsDescribeCompleteCenteredPreview() throws {
        let crop = try CropRect(arguments: [:])

        XCTAssertEqual(crop.scaleWidth, 1)
        XCTAssertEqual(crop.scaleHeight, 1)
        XCTAssertEqual(crop.offsetX, 0)
        XCTAssertEqual(crop.offsetY, 0)
    }

    func testCodecNumbersPreserveConfiguredGeometry() throws {
        let crop = try CropRect(arguments: [
            "scaleWidth": NSNumber(value: 0.5),
            "scaleHeight": NSNumber(value: 0.75),
            "offsetX": NSNumber(value: -0.2),
            "offsetY": NSNumber(value: 0.4),
        ])

        XCTAssertEqual(crop.scaleWidth, 0.5)
        XCTAssertEqual(crop.scaleHeight, 0.75)
        XCTAssertEqual(crop.offsetX, -0.2)
        XCTAssertEqual(crop.offsetY, 0.4)
    }

    func testCodecZeroAndOnePreserveFullPreviewGeometry() throws {
        let crop = try CropRect(arguments: [
            "scaleWidth": NSNumber(value: 1.0), "scaleHeight": NSNumber(value: 1.0),
            "offsetX": NSNumber(value: 0.0), "offsetY": NSNumber(value: 0.0),
        ])

        XCTAssertEqual(crop.scaleWidth, 1)
        XCTAssertEqual(crop.scaleHeight, 1)
        XCTAssertEqual(crop.offsetX, 0)
        XCTAssertEqual(crop.offsetY, 0)
    }

    func testNullCoordinatesUseFullPreviewDefaults() throws {
        let crop = try CropRect(arguments: [
            "scaleWidth": NSNull(), "scaleHeight": NSNull(), "offsetX": NSNull(), "offsetY": NSNull(),
        ])

        XCTAssertEqual(crop.scaleWidth, 1)
        XCTAssertEqual(crop.scaleHeight, 1)
        XCTAssertEqual(crop.offsetX, 0)
        XCTAssertEqual(crop.offsetY, 0)
    }

    func testInvalidGeometryIsRejected() {
        let invalidValues: [[String: Any]] = [
            ["scaleWidth": "bad"],
            ["scaleHeight": true],
            ["offsetX": Double.nan],
            ["offsetY": Double.infinity],
            ["scaleWidth": NSNumber(value: true)],
            ["scaleHeight": Double.infinity],
            ["offsetY": -Double.infinity],
        ]

        for value in invalidValues {
            XCTAssertThrowsError(try CropRect(arguments: value)) {
                XCTAssertEqual($0 as? MlKitPluginError, .invalidArguments)
            }
        }
    }
}
