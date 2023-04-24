import 'package:flutter/foundation.dart';
import 'package:mlkit_scanner/models/crop_rect.dart';

/// Parameters for initializing the scanner.
abstract class ScannerParameters {
  /// Optional initial scanner overlay with [CropRect] of the detection area.
  final CropRect? cropRect;

  const ScannerParameters({this.cropRect});

  /// Converting an object into a format for transferring to native.
  @mustCallSuper
  Map<String, dynamic> toJson() {
    return {
      'initialCropRect': cropRect?.toJson(),
    };
  }
}
