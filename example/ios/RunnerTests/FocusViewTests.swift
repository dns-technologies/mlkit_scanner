import UIKit
import XCTest
@testable import mlkit_scanner

final class FocusViewTests: XCTestCase {
    override func setUp() {
        super.setUp()
        UIView.setAnimationsEnabled(false)
    }

    override func tearDown() {
        UIView.setAnimationsEnabled(true)
        super.tearDown()
    }

    func testAddingToSuperviewAdoptsContainerBoundsAndFlexibleSizing() {
        let container = UIView(frame: CGRect(x: 0, y: 0, width: 200, height: 100))
        let focus = FocusView(frame: .zero, point: CGPoint(x: 100, y: 50))

        container.addSubview(focus)

        XCTAssertEqual(focus.frame, container.bounds)
        XCTAssertTrue(focus.autoresizingMask.contains(.flexibleWidth))
        XCTAssertTrue(focus.autoresizingMask.contains(.flexibleHeight))
        XCTAssertEqual(focus.gestureRecognizers?.count, 2)
    }

    func testTapAndBeganLongPressNotifyTheirDistinctDelegateMethods() {
        let focus = FocusView(
            frame: CGRect(x: 0, y: 0, width: 200, height: 100),
            point: CGPoint(x: 100, y: 50)
        )
        let delegate = FocusDelegateRecorder()
        focus.delegate = delegate
        let tap = focus.gestureRecognizers?.compactMap { $0 as? UITapGestureRecognizer }.first
        let longPress = BeganLongPressGestureRecognizer()
        longPress.begin()

        focus.perform(NSSelectorFromString("onTap:"), with: tap)
        focus.perform(NSSelectorFromString("onLongTap:"), with: longPress)

        XCTAssertEqual(delegate.focusCount, 1)
        XCTAssertEqual(delegate.lockCount, 1)
        focus.cancelLockFocus()
    }

    func testMoveFocusIgnoresNonFiniteCoordinates() {
        let focus = FocusView(
            frame: CGRect(x: 0, y: 0, width: 200, height: 100),
            point: CGPoint(x: 100, y: 50)
        )
        let circle = focus.layer.sublayers?.compactMap { $0 as? CAShapeLayer }.first
        let originalBounds = circle?.path?.boundingBox

        focus.moveFocus(to: CGPoint(x: CGFloat.nan, y: CGFloat.infinity))

        XCTAssertEqual(circle?.path?.boundingBox, originalBounds)
    }
}

private final class FocusDelegateRecorder: NSObject, FocusViewDelegate {
    var focusCount = 0
    var lockCount = 0

    func onFocus() {
        focusCount += 1
    }

    func onLockFocus() {
        lockCount += 1
    }
}

private final class BeganLongPressGestureRecognizer: UILongPressGestureRecognizer {
    func begin() {
        state = .began
    }
}
