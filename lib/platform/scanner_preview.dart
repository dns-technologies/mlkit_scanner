import 'dart:ui';

/// Availability of displayable frames in a registered texture.
enum ScannerPreviewStatus {
  /// Output is registered but no usable frame has arrived yet.
  starting,

  /// Native capture is producing preview frames.
  streaming,

  /// Capture is stopped while its last frame remains available.
  paused,
}

/// Texture identity, dimensions, display transforms and frame availability.
class ScannerPreviewDescription {
  const ScannerPreviewDescription({
    required this.textureId,
    required this.size,
    this.rotationDegrees = 0,
    this.mirrored = false,
    this.cropRect,
    required this.status,
  });

  factory ScannerPreviewDescription.fromJson(Map values) {
    final id = values['textureId'];
    final width = values['width'];
    final height = values['height'];
    final rotation = values['rotationDegrees'];
    final mirrored = values['mirrored'];
    if (id is! int ||
        id < 0 ||
        width is! num ||
        height is! num ||
        !width.isFinite ||
        !height.isFinite ||
        width <= 0 ||
        height <= 0 ||
        !const [0, 90, 180, 270].contains(rotation) ||
        mirrored is! bool) {
      throw const FormatException('Invalid scanner texture description');
    }
    final size = Size(width.toDouble(), height.toDouble());
    return ScannerPreviewDescription(
      textureId: id,
      cropRect: _decodeCrop(values, size),
      size: size,
      rotationDegrees: rotation as int,
      mirrored: mirrored,
      status: ScannerPreviewStatus.values.byName(values['state'] as String),
    );
  }

  /// Texture registry handle identifying the preview output.
  final int textureId;

  /// Native output dimensions before the remaining crop and rotation.
  final Size size;

  /// Clockwise quarter-turn rotation required to display the preview upright.
  final int rotationDegrees;

  /// Whether the oriented preview requires horizontal reflection.
  final bool mirrored;

  /// Current availability of live or retained preview frames.
  final ScannerPreviewStatus status;

  /// Visible source rectangle in texture pixels, before display rotation.
  final Rect? cropRect;

  /// Rejects source crops that are empty, nonfinite or outside the texture.
  static Rect? _decodeCrop(Map values, Size sourceSize) {
    if (!values.containsKey('cropLeft')) return null;
    final left = values['cropLeft'];
    final top = values['cropTop'];
    final width = values['cropWidth'];
    final height = values['cropHeight'];
    if (left is! num ||
        top is! num ||
        width is! num ||
        height is! num ||
        !left.isFinite ||
        !top.isFinite ||
        !width.isFinite ||
        !height.isFinite ||
        left < 0 ||
        top < 0 ||
        width <= 0 ||
        height <= 0 ||
        left + width > sourceSize.width ||
        top + height > sourceSize.height) {
      throw const FormatException('Invalid texture crop');
    }
    return Rect.fromLTWH(left.toDouble(), top.toDouble(), width.toDouble(), height.toDouble());
  }
}
