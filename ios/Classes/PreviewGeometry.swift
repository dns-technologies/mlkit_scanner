import CoreGraphics

/// Resolves finite focus geometry independently from the platform-view layout phase.
enum PreviewGeometry {
    /// Returns a capture-device point from preview-center offsets.
    ///
    /// Capture-device focus coordinates must stay in the closed unit square.
    static func normalizedFocusPoint(offsetX: CGFloat, offsetY: CGFloat) -> CGPoint {
        CGPoint(
            x: normalizedCoordinate(for: offsetX),
            y: normalizedCoordinate(for: offsetY)
        )
    }

    /// Returns a local preview point from normalized focus coordinates.
    static func focusPosition(in bounds: CGRect, normalizedPoint: CGPoint) -> CGPoint {
        guard bounds.isFinite,
              normalizedPoint.x.isFinite,
              normalizedPoint.y.isFinite else {
            return .zero
        }
        return CGPoint(
            x: bounds.minX + bounds.width * normalizedPoint.x,
            y: bounds.minY + bounds.height * normalizedPoint.y
        )
    }

    /// Returns whether UIKit can safely use these bounds for preview layout.
    static func isLayoutReady(_ bounds: CGRect) -> Bool {
        bounds.isFinite && bounds.width > 0 && bounds.height > 0
    }

    private static func normalizedCoordinate(for offset: CGFloat) -> CGFloat {
        guard offset.isFinite else { return 0.5 }
        return min(max((1 + offset) / 2, 0), 1)
    }
}

private extension CGRect {
    var isFinite: Bool {
        origin.x.isFinite
            && origin.y.isFinite
            && size.width.isFinite
            && size.height.isFinite
    }
}
