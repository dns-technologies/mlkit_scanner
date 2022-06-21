///  Model for setting detection area of recognizers.
///
/// If detection area is bigger than [CameraPreview] size, there won't any detection.
class CropRect {
  /// Width relative to width of the [CameraPreview] in percentage.
  ///
  /// For example: 0.5 -  widgth of detection area equals to half of the [CameraPreview] width.
  final double scaleWidth;

  /// Height relative to height of the [CameraPreview] in percentage.
  ///
  /// For example: 1 -  height of detection area equals to the [CameraPreview] height.
  final double scaleHeight;

  /// X-axis offset in percentage from centerX of [CameraPreview] size rect.
  ///
  /// For example: Coordinate of the centerX is 3. Whole lenght is 6. Coordinates of the crop area centerX
  /// if 4.5. Offset equals: (4.5 - 3) / 3 = 0.5. Offset forward by 50 %.
  /// If `offsetX == 0` then centerX of the [CropRect] equals centerX of the [CameraPreview] size rect.
  final double offsetX;

  /// Y-axis offset in percentage from center Y of [CameraPreview] size rect.
  ///
  /// For example: Coordinate of the centerY is 3. Whole lenght is 6. Coordinates of the crop area centerY
  /// if 1.5. Offset equals: (1.5 - 3) / 3 = -0.5. Offset back by 50 %.
  /// If `offsetY == 0` then centerY of the [CropRect] equals centerY of the [CameraPreview] size rect.
  final double offsetY;

  const CropRect({
    this.scaleWidth = 1,
    this.scaleHeight = 1,
    this.offsetX = 0,
    this.offsetY = 0,
  });

  Map<String, double> toJson() {
    return {
      'scaleHeight': scaleHeight,
      'scaleWidth': scaleWidth,
      'offsetX': offsetX,
      'offsetY': offsetY,
    };
  }
}
