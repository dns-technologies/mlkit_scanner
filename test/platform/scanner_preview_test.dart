import 'dart:ui';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/platform/scanner_preview.dart';

void main() {
  Map<String, Object> description(int id) => {
    'textureId': id,
    'width': 1280,
    'height': 720,
    'rotationDegrees': 90,
    'mirrored': false,
    'state': 'streaming',
  };

  test('native crop and paused state are preserved by the descriptor', () {
    final data = description(9)..addAll({'cropLeft': 160, 'cropTop': 0, 'cropWidth': 960, 'cropHeight': 720, 'state': 'paused'});
    final preview = ScannerPreviewDescription.fromJson(data);
    expect(preview.cropRect, const Rect.fromLTWH(160, 0, 960, 720));
    expect(preview.status, ScannerPreviewStatus.paused);
    expect(() => ScannerPreviewDescription.fromJson(data..['cropWidth'] = 2000), throwsFormatException);
  });
}
