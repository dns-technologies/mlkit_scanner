import Foundation
import XCTest
@testable import mlkit_scanner

final class ScannerMethodArgumentsTests: XCTestCase {
    func testViewRegistrationParsesOptionalTypedControls() throws {
        let registration = try ScannerMethodArguments.viewRegistration([
            "width": 320.0,
            "height": 480.0,
            "initialZoomRatio": 2.0,
            "initialFlashEnabled": true,
            "initialCropRect": [
                "scaleWidth": 0.5,
                "scaleHeight": 0.6,
                "offsetX": 0.1,
                "offsetY": -0.2,
            ],
        ])

        XCTAssertEqual(registration.size, CGSize(width: 320, height: 480))
        XCTAssertEqual(registration.initialZoomRatio, 2)
        XCTAssertEqual(registration.initialFlashEnabled, true)
        XCTAssertEqual(registration.initialCropRect?.scaleWidth, 0.5)
        XCTAssertEqual(registration.initialCropRect?.scaleHeight, 0.6)
        XCTAssertEqual(registration.initialCropRect?.offsetX, 0.1)
        XCTAssertEqual(registration.initialCropRect?.offsetY, -0.2)
    }

    func testZeroCreationSizeDefersToUIKitLayout() throws {
        let registration = try ScannerMethodArguments.viewRegistration([
            "width": 0.0,
            "height": 0.0,
        ])

        XCTAssertNil(registration.size)
    }

    func testScanOptionsRequireKnownTypeAndNonnegativeIntegerDelay() throws {
        let options = try ScannerMethodArguments.scanOptions([
            "viewId": 42,
            "type": 0,
            "delay": 150,
        ])

        XCTAssertEqual(options.viewId, 42)
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
                "delay": -1,
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

        XCTAssertEqual(value.viewId, 42)
        XCTAssertEqual(value.value, 3)
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

        XCTAssertEqual(value.value.scaleWidth, 1)
        XCTAssertEqual(value.value.scaleHeight, 1)
        XCTAssertEqual(value.value.offsetX, 0)
        XCTAssertEqual(value.value.offsetY, 0)

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
