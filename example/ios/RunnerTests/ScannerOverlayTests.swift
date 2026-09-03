import UIKit
import XCTest
@testable import mlkit_scanner

final class ScannerOverlayTests: XCTestCase {
    func testOverlayFillsSuperviewAndRetainsNormalizedCrop() throws {
        let crop = try CropRect(arguments: ["scaleWidth": 0.5, "scaleHeight": 0.4])
        let container = UIView(frame: CGRect(x: 0, y: 0, width: 200, height: 100))
        let overlay = ScannerOverlay(cropRect: crop)

        container.addSubview(overlay)
        overlay.layoutIfNeeded()

        XCTAssertEqual(overlay.frame, container.bounds)
        XCTAssertEqual(overlay.cropRect.scaleWidth, 0.5)
        XCTAssertEqual(overlay.cropRect.scaleHeight, 0.4)
        XCTAssertEqual(overlay.backgroundColor, .clear)
    }

    func testCropAndActiveStateCanChangeAfterLayoutWithoutInvalidGeometry() throws {
        let overlay = ScannerOverlay(cropRect: try CropRect(arguments: [:]))
        overlay.frame = CGRect(x: 0, y: 0, width: 200, height: 100)
        let crop = try CropRect(arguments: [
            "scaleWidth": 0.4,
            "scaleHeight": 0.6,
            "offsetX": 0.25,
            "offsetY": -0.5,
        ])

        overlay.updateCropRect(rect: crop)
        overlay.isActive = true
        overlay.layoutIfNeeded()
        let renderer = UIGraphicsImageRenderer(size: overlay.bounds.size)
        let image = renderer.image { context in
            overlay.layer.render(in: context.cgContext)
        }

        XCTAssertEqual(overlay.cropRect.offsetX, 0.25)
        XCTAssertEqual(overlay.cropRect.offsetY, -0.5)
        XCTAssertTrue(overlay.isActive)
        XCTAssertEqual(image.size, overlay.bounds.size)
    }
}
