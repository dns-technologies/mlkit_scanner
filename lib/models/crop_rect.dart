/// A normalized barcode recognition rectangle relative to the camera preview.
///
/// Scales describe size relative to the preview; offsets describe displacement
/// of the rectangle's center from the preview center.
class CropRect {
  const CropRect({this.scaleWidth = 1, this.scaleHeight = 1, this.offsetX = 0, this.offsetY = 0});

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

  /// Whether scales are positive and all coordinates are finite.
  /// A valid rectangle may extend beyond the preview bounds.
  bool get isValid =>
      scaleWidth.isFinite && scaleWidth > 0 && scaleHeight.isFinite && scaleHeight > 0 && offsetX.isFinite && offsetY.isFinite;

  /// Converts this rectangle to its platform-channel representation.
  Map<String, double> toJson() {
    return {'scaleHeight': scaleHeight, 'scaleWidth': scaleWidth, 'offsetX': offsetX, 'offsetY': offsetY};
  }
}
