import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';
import 'package:mlkit_scanner/platform/scanner_configuration.dart';

void main() {
  test('default snapshot is complete without native retained state', () {
    expect(const ScannerConfiguration().toJson(), {
      'zoomRatio': 1.0,
      'torchEnabled': false,
      'cropRect': null,
      'scanEnabled': false,
      'scanDelay': 0,
    });
  });

  test(
      'updating one value preserves all other settings without mutating the old snapshot',
      () {
    const original = ScannerConfiguration(
        zoomRatio: 2, scanDelay: 150, cropRect: CropRect(scaleWidth: 0.5));
    final updated = original.copyWith(torchEnabled: true, scanEnabled: true);
    expect(original.torchEnabled, isFalse);
    expect(original.scanEnabled, isFalse);
    expect(updated.zoomRatio, 2);
    expect(updated.scanDelay, 150);
    expect(updated.cropRect, same(original.cropRect));
    expect(updated.torchEnabled, isTrue);
    expect(updated.scanEnabled, isTrue);
  });

  test('iOS camera selection travels with the same full capture snapshot', () {
    const configuration = ScannerConfiguration(
      iosCamera: IosCamera(
          position: IosCameraPosition.back,
          type: IosCameraType.builtInWideAngleCamera),
    );
    expect(configuration.toJson()['iosCamera'], {'position': 1, 'type': 0});
    expect(
        configuration.toJson().keys,
        containsAll([
          'zoomRatio',
          'torchEnabled',
          'cropRect',
          'scanEnabled',
          'scanDelay'
        ]));
  });
}
