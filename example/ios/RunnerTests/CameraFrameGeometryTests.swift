import XCTest
@testable import mlkit_scanner

final class CameraFrameGeometryTests: XCTestCase {
    func testPortraitCoverUsesCenteredRegionOfOrientedBuffer() {
        let geometry = CameraFrameGeometry(source: CGSize(width: 720, height: 1280), viewport: CGSize(width: 400, height: 800))
        XCTAssertEqual(geometry.visible, CGRect(x: 40, y: 0, width: 640, height: 1280))
        XCTAssertEqual(geometry.normalizedPoint(CGPoint(x: 0.5, y: 0.5)), CGPoint(x: 0.5, y: 0.5))
    }
    func testOffCenterCropClipsToVisibleBuffer() throws {
        let geometry = CameraFrameGeometry(source: CGSize(width: 1000, height: 1000), viewport: CGSize(width: 400, height: 400))
        let crop = try CropRect(arguments: ["scaleWidth": 0.5, "scaleHeight": 0.5, "offsetX": 0.8, "offsetY": 0])
        XCTAssertEqual(geometry.recognitionBounds(crop), CGRect(x: 650, y: 250, width: 350, height: 500))
    }
}
