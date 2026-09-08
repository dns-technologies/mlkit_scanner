import 'package:mlkit_scanner/models/crop_rect.dart';
import 'package:mlkit_scanner/models/ios_camera.dart';
import 'package:mlkit_scanner/models/ios_camera_position.dart';
import 'package:mlkit_scanner/models/ios_camera_type.dart';

/// Desired settings of one Dart scanner, including while another view is active.
class ScannerConfiguration {
  final double zoomRatio;
  final bool torchEnabled;
  final CropRect? cropRect;
  final bool scanEnabled;
  final int scanDelay;
  final IosCamera? iosCamera;

  const ScannerConfiguration({
    this.zoomRatio = 1,
    this.torchEnabled = false,
    this.cropRect,
    this.scanEnabled = false,
    this.scanDelay = 0,
    this.iosCamera,
  });

  ScannerConfiguration copyWith({
    double? zoomRatio,
    bool? torchEnabled,
    CropRect? cropRect,
    bool? scanEnabled,
    int? scanDelay,
    IosCamera? iosCamera,
  }) =>
      ScannerConfiguration(
        zoomRatio: zoomRatio ?? this.zoomRatio,
        torchEnabled: torchEnabled ?? this.torchEnabled,
        cropRect: cropRect ?? this.cropRect,
        scanEnabled: scanEnabled ?? this.scanEnabled,
        scanDelay: scanDelay ?? this.scanDelay,
        iosCamera: iosCamera ?? this.iosCamera,
      );

  /// A complete input for one native capture, not a native per-view state store.
  Map<String, Object?> toJson() => {
        'zoomRatio': zoomRatio,
        'torchEnabled': torchEnabled,
        'cropRect': cropRect?.toJson(),
        'scanEnabled': scanEnabled,
        'scanDelay': scanDelay,
        if (iosCamera case final camera?)
          'iosCamera': {
            'position': camera.position.code,
            'type': camera.type.code,
          },
      };
}
