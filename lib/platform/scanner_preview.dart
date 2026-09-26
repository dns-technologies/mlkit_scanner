import 'dart:ui';

/// Whether a registered texture has a frame that Flutter can display.
enum ScannerPreviewStatus {
  /// Output is registered but no usable frame has arrived yet.
  starting,

  /// Native capture is producing preview frames.
  streaming,

  /// Capture is stopped while its last frame remains available.
  paused,
}

/// Metadata for a registered native texture. Pixel storage never crosses Dart.
class ScannerPreviewDescription {
  /// Flutter texture registry handle for the shared native output.
  final int textureId;

  /// Native output dimensions before the remaining crop and rotation.
  final Size size;

  /// Clockwise quarter-turn rotation still required on the Flutter side.
  final int rotationDegrees;

  /// Whether Flutter must reflect the oriented preview horizontally.
  final bool mirrored;

  /// Controls live preview availability, paused rendering and frame retention.
  final ScannerPreviewStatus status;

  /// Source-pixel crop not already applied by the native texture backend.
  final Rect? cropRect;

  /// Describes an allocated output without transferring its pixel storage.
  const ScannerPreviewDescription({
    required this.textureId,
    required this.size,
    this.rotationDegrees = 0,
    this.mirrored = false,
    this.cropRect,
    required this.status,
  });

  /// Validates native metadata before allowing it to reach Flutter layout.
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
