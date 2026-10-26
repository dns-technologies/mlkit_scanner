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

    func testFullAndMissingCropUseVisibleBufferAcrossOrientations() {
        let cases: [(CGSize, CGSize, CGRect)] = [
            (CGSize(width: 720, height: 1280), CGSize(width: 400, height: 800),
             CGRect(x: 40, y: 0, width: 640, height: 1280)),
            (CGSize(width: 1280, height: 720), CGSize(width: 800, height: 400),
             CGRect(x: 0, y: 40, width: 1280, height: 640)),
            (CGSize(width: 720, height: 1280), CGSize(width: 360, height: 640),
             CGRect(x: 0, y: 0, width: 720, height: 1280)),
        ]

        for (source, viewport, expected) in cases {
            let geometry = CameraFrameGeometry(source: source, viewport: viewport)

            XCTAssertEqual(geometry.recognitionBounds(nil), expected)
            XCTAssertEqual(geometry.recognitionBounds(CropRect()), expected)
        }
    }

    func testOversizedCropCannotRecognizeHiddenBufferEdges() throws {
        let geometry = CameraFrameGeometry(
            source: CGSize(width: 720, height: 1280),
            viewport: CGSize(width: 400, height: 800)
        )
        let crop = try CropRect(arguments: [
            "scaleWidth": 2, "scaleHeight": 2, "offsetX": 1, "offsetY": 1,
        ])

        XCTAssertEqual(geometry.recognitionBounds(crop), CGRect(x: 40, y: 0, width: 640, height: 1280))
    }

    func testFullyOutsideCropHasNoRecognitionArea() throws {
        let geometry = CameraFrameGeometry(
            source: CGSize(width: 720, height: 1280),
            viewport: CGSize(width: 400, height: 800)
        )
        let crop = try CropRect(arguments: [
            "scaleWidth": 0.2, "scaleHeight": 0.2, "offsetX": 3, "offsetY": 3,
        ])

        XCTAssertTrue(geometry.recognitionBounds(crop).isEmpty)
    }

    func testFocusPointsAreClampedToTheVisibleBufferEdges() {
        let geometry = CameraFrameGeometry(
            source: CGSize(width: 720, height: 1280),
            viewport: CGSize(width: 400, height: 800)
        )

        XCTAssertEqual(geometry.normalizedPoint(CGPoint(x: -1, y: -1)), CGPoint(x: 40.0 / 720, y: 0))
        XCTAssertEqual(geometry.normalizedPoint(CGPoint(x: 2, y: 2)), CGPoint(x: 680.0 / 720, y: 1))
    }
}
