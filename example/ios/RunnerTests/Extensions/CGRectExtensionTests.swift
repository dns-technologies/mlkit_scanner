import AVFoundation
import XCTest
@testable import mlkit_scanner

final class CGRectExtensionTests: XCTestCase {
    func testPortraitCropUsesWidthScaleHeightAdjustmentAndMatchingOffsets() throws {
        let crop = try CropRect(arguments: [
            "scaleWidth": 0.5,
            "scaleHeight": 0.25,
            "offsetX": 0.2,
            "offsetY": -0.4,
        ])

        let result = CGRect(x: 0, y: 0, width: 200, height: 100).cropBy(
            cropRect: crop,
            orientation: .portrait,
            scaleX: 0.5,
            scaleY: 0.25
        )

        XCTAssertEqual(result, CGRect(x: 60, y: 30, width: 100, height: 30))
    }

    func testLandscapeCropSwapsScalesAndOffsets() throws {
        let crop = try CropRect(arguments: [
            "scaleWidth": 0.5,
            "scaleHeight": 0.25,
            "offsetX": 0.2,
            "offsetY": -0.4,
        ])

        let result = CGRect(x: 0, y: 0, width: 200, height: 100).cropBy(
            cropRect: crop,
            orientation: .landscapeLeft,
            scaleX: 0.5,
            scaleY: 0.25
        )

        XCTAssertEqual(result, CGRect(x: 60, y: 30, width: 60, height: 50))
    }
}
