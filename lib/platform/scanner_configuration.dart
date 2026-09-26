import 'package:mlkit_scanner/models/crop_rect.dart';
import 'package:mlkit_scanner/models/ios_camera.dart';
import 'package:mlkit_scanner/models/ios_camera_position.dart';
import 'package:mlkit_scanner/models/ios_camera_type.dart';

/// Desired settings of one Dart scanner, including while another view is active.
class ScannerConfiguration {
  /// Warm preview/recognition pause, retained across route and app lifecycle changes.
  final bool cameraPaused;

  /// Absolute camera zoom ratio, where 1 is the default magnification.
  final double zoomRatio;

  /// Desired torch state, reapplied when ownership changes.
  final bool torchEnabled;

  /// Recognition area; null uses the full preview.
  final CropRect? cropRect;

  /// Whether recognition should run after camera activation.
  final bool scanEnabled;

  /// Cooldown after a successful recognition, in milliseconds.
  final int scanDelay;

  /// Physical iOS camera selection; null uses the platform default.
  final IosCamera? iosCamera;

  const ScannerConfiguration({
    this.cameraPaused = false,
    this.zoomRatio = 1,
    this.torchEnabled = false,
    this.cropRect,
    this.scanEnabled = false,
    this.scanDelay = 0,
    this.iosCamera,
  });

  /// Compares retained values, treating an omitted crop as the full preview.
  bool hasSameSettings(ScannerConfiguration other) =>
      cameraPaused == other.cameraPaused &&
      hasSameCameraSettings(other) &&
      scanEnabled == other.scanEnabled &&
      scanDelay == other.scanDelay &&
      hasSameCamera(other);

  /// Compares controls that can change together without camera activation.
  bool hasSameCameraSettings(ScannerConfiguration other) =>
      zoomRatio == other.zoomRatio && torchEnabled == other.torchEnabled && hasSameCrop(other);

  /// Compares physical camera selections without depending on object identity.
  bool hasSameCamera(ScannerConfiguration other) =>
      iosCamera?.position == other.iosCamera?.position && iosCamera?.type == other.iosCamera?.type;

  /// Compares recognition areas, treating an omitted crop as the full preview.
  bool hasSameCrop(ScannerConfiguration other) {
    final crop = cropRect ?? const CropRect();
    final otherCrop = other.cropRect ?? const CropRect();
    return crop.scaleWidth == otherCrop.scaleWidth &&
        crop.scaleHeight == otherCrop.scaleHeight &&
        crop.offsetX == otherCrop.offsetX &&
        crop.offsetY == otherCrop.offsetY;
  }

  /// Copies settings, retaining existing values for omitted or null arguments.
  ScannerConfiguration copyWith({
    bool? cameraPaused,
    double? zoomRatio,
    bool? torchEnabled,
    CropRect? cropRect,
    bool? scanEnabled,
    int? scanDelay,
    IosCamera? iosCamera,
  }) => ScannerConfiguration(
    cameraPaused: cameraPaused ?? this.cameraPaused,
    zoomRatio: zoomRatio ?? this.zoomRatio,
    torchEnabled: torchEnabled ?? this.torchEnabled,
    cropRect: cropRect ?? this.cropRect,
    scanEnabled: scanEnabled ?? this.scanEnabled,
    scanDelay: scanDelay ?? this.scanDelay,
    iosCamera: iosCamera ?? this.iosCamera,
  );

  /// Camera settings for activation; Dart controls recognition through separate commands.
  Map<String, Object?> toCaptureArguments() => {
    'zoomRatio': zoomRatio,
    'torchEnabled': torchEnabled,
    'cropRect': cropRect?.toJson(),
    if (iosCamera case final camera?) 'iosCamera': {'position': camera.position.code, 'type': camera.type.code},
  };
}
