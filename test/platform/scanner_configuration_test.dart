import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';
import 'package:mlkit_scanner/platform/scanner_configuration.dart';

void main() {
  test('capture contains camera settings without Dart recognition intent', () {
    expect(const ScannerConfiguration(cameraPaused: true, scanEnabled: true, scanDelay: 250).toCaptureArguments(), {
      'zoomRatio': 1.0,
      'torchEnabled': false,
      'cropRect': null,
    });
  });

  test('updating one value preserves all other settings without mutating the old snapshot', () {
    const original = ScannerConfiguration(zoomRatio: 2, scanDelay: 150, cropRect: CropRect(scaleWidth: 0.5));
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
      iosCamera: IosCamera(position: IosCameraPosition.back, type: IosCameraType.builtInWideAngleCamera),
    );
    expect(configuration.toCaptureArguments()['iosCamera'], {'position': 1, 'type': 0});
    expect(configuration.toCaptureArguments().keys, containsAll(['zoomRatio', 'torchEnabled', 'cropRect']));
  });
}
