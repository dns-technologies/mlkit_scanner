/// A normalized barcode recognition rectangle relative to the camera preview.
///
/// The part of the rectangle inside the camera preview is analyzed.
/// Detection is skipped only when the area is completely outside the preview.
class CropRect {
  /// Rectangle width as a fraction of the preview width.
  ///
  /// For example, `0.5` makes the recognition area half as wide as the
  /// preview.
  final double scaleWidth;

  /// Rectangle height as a fraction of the preview height.
  ///
  /// For example, `1` makes the recognition area as tall as the preview.
  final double scaleHeight;

  /// Horizontal center offset normalized to half the preview width.
  ///
  /// `0` centers the rectangle. `1` moves its center to the right edge, and
  /// `-1` moves its center to the left edge.
  final double offsetX;

  /// Vertical center offset normalized to half the preview height.
  ///
  /// `0` centers the rectangle. `1` moves its center to the bottom edge, and
  /// `-1` moves its center to the top edge.
  final double offsetY;

  /// Creates a normalized recognition rectangle.
  ///
  /// Width and height scales must be positive when sent to the native plugin.
  const CropRect({
    this.scaleWidth = 1,
    this.scaleHeight = 1,
    this.offsetX = 0,
    this.offsetY = 0,
  });

  /// Converts this rectangle to its platform-channel representation.
  Map<String, double> toJson() {
    return {
      'scaleHeight': scaleHeight,
      'scaleWidth': scaleWidth,
      'offsetX': offsetX,
      'offsetY': offsetY,
    };
  }
}
