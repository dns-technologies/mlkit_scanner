import 'package:mlkit_scanner/models/crop_rect.dart';
import 'package:mlkit_scanner/models/scanner_parameters.dart';

/// Parameters for initializing the scanner on Android.
class AndroidScannerParameters extends ScannerParameters {
  /// Optional initial zoom.
  final double? zoom;

  const AndroidScannerParameters({this.zoom, CropRect? cropRect})
      : super(cropRect: cropRect);

  @override
  Map<String, dynamic> toJson() {
    return {
      'initialZoom': zoom,
      ...super.toJson(),
    };
  }
}
