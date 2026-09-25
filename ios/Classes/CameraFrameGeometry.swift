import CoreGraphics

/// Coordinates in the physically oriented, optionally mirrored video buffer shown by Flutter.
struct CameraFrameGeometry {
    /// Dimensions of the physically oriented camera buffer.
    let source: CGSize
    /// Flutter viewport dimensions used for centered cover fitting.
    let viewport: CGSize
    /// Source rectangle visible through the centered cover-fit viewport.
    var visible: CGRect {
        let scale = max(viewport.width / source.width, viewport.height / source.height)
        let width = viewport.width / scale
        let height = viewport.height / scale
        return CGRect(x: (source.width - width) / 2, y: (source.height - height) / 2, width: width, height: height)
    }

    /// Maps a clamped viewport point into normalized source coordinates.
    func normalizedPoint(_ point: CGPoint) -> CGPoint {
        let rect = visible
        return CGPoint(x: (rect.minX + min(1, max(0, point.x)) * rect.width) / source.width,
                       y: (rect.minY + min(1, max(0, point.y)) * rect.height) / source.height)
    }

    /// Clips the requested recognition area to the visible camera buffer.
    func recognitionBounds(_ crop: CropRect?) -> CGRect {
        let rect = visible
        guard let crop = crop else { return rect }
        let width = rect.width * crop.scaleWidth
        let height = rect.height * crop.scaleHeight
        let target = CGRect(x: rect.midX + rect.width * crop.offsetX / 2 - width / 2,
            y: rect.midY + rect.height * crop.offsetY / 2 - height / 2, width: width, height: height)
        return target.intersection(rect).intersection(CGRect(origin: .zero, size: source))
    }
}
