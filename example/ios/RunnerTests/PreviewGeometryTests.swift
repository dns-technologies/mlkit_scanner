import CoreGraphics
import XCTest
@testable import mlkit_scanner

final class PreviewGeometryTests: XCTestCase {
    func testZeroBoundsProduceFiniteFocusPosition() {
        let point = PreviewGeometry.focusPosition(
            in: .zero,
            normalizedPoint: PreviewGeometry.normalizedFocusPoint(offsetX: 0, offsetY: 0)
        )

        XCTAssertEqual(point, .zero)
        XCTAssertTrue(point.x.isFinite)
        XCTAssertTrue(point.y.isFinite)
        XCTAssertFalse(PreviewGeometry.isLayoutReady(.zero))
    }

    func testFocusOffsetsAreNormalizedAndClampedWithoutDividingByBounds() {
        let point = PreviewGeometry.normalizedFocusPoint(offsetX: 4, offsetY: -4)
        let position = PreviewGeometry.focusPosition(
            in: CGRect(x: 10, y: 20, width: 200, height: 100),
            normalizedPoint: point
        )

        XCTAssertEqual(point, CGPoint(x: 1, y: 0))
        XCTAssertEqual(position, CGPoint(x: 210, y: 20))
    }

    func testNonfiniteOffsetsFallBackToPreviewCenter() {
        let point = PreviewGeometry.normalizedFocusPoint(
            offsetX: .nan,
            offsetY: .infinity
        )

        XCTAssertEqual(point, CGPoint(x: 0.5, y: 0.5))
    }
}
