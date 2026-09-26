import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/barcode.dart';
import 'package:mlkit_scanner/models/crop_rect.dart';
import 'package:mlkit_scanner/models/ios_camera.dart';
import 'package:mlkit_scanner/models/ios_camera_position.dart';
import 'package:mlkit_scanner/models/ios_camera_type.dart';
import 'package:mlkit_scanner/platform/scanner_configuration.dart';
import 'package:mlkit_scanner/src/platform/scanner_controller.dart';

import '../support/runtime_harness.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late RuntimeHarness harness;
  late BarcodeScannerController controller;
  setUp(() {
    harness = RuntimeHarness();
    controller = harness.controller(17);
  });
  tearDown(() async {
    debugDefaultTargetPlatformOverride = null;
    await harness.dispose();
  });

  group('configuration', () {
    test('retains a complete immutable snapshot without an active session', () {
      final initial = controller.configuration;
      const next = ScannerConfiguration(zoomRatio: 3, torchEnabled: true, scanEnabled: true, scanDelay: 250);
      controller.applyConfiguration(next);
      expect(controller.viewId, 17);
      expect(controller.configuration, same(next));
      expect(initial.zoomRatio, 1);
      expect(initial.torchEnabled, isFalse);
    });

    test('equivalent settings preserve snapshot identity', () {
      final initial = controller.configuration;
      controller.applyConfiguration(const ScannerConfiguration(cropRect: CropRect()));
      expect(controller.configuration, same(initial));
    });

    test('nullable parameters reset previous crop and camera selection', () {
      debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
      controller.applyConfiguration(
        const ScannerConfiguration(
          cropRect: CropRect(scaleWidth: .5),
          iosCamera: IosCamera(position: IosCameraPosition.back, type: IosCameraType.builtInWideAngleCamera),
        ),
      );
      controller.applyConfiguration(const ScannerConfiguration());
      expect(controller.configuration.cropRect, isNull);
      expect(controller.configuration.iosCamera, isNull);
    });

    test('invalid settings reject the whole snapshot without partial updates', () {
      final original = controller.configuration;
      for (final delay in [-1, 0x80000000]) {
        expect(() => controller.applyConfiguration(ScannerConfiguration(zoomRatio: 3, scanDelay: delay)), throwsRangeError);
      }
      for (final zoom in [0.0, -1.0, double.nan, double.infinity]) {
        expect(() => controller.applyConfiguration(ScannerConfiguration(zoomRatio: zoom, torchEnabled: true)), throwsArgumentError);
      }
      for (final crop in [
        const CropRect(scaleWidth: 0),
        const CropRect(scaleHeight: -1),
        const CropRect(scaleWidth: double.nan),
        const CropRect(scaleHeight: double.infinity),
        const CropRect(offsetX: double.nan),
        const CropRect(offsetY: double.infinity),
      ]) {
        expect(() => controller.applyConfiguration(ScannerConfiguration(cropRect: crop, torchEnabled: true)), throwsArgumentError);
      }
      expect(controller.configuration, same(original));
    });

    test('delay boundary values are accepted', () {
      controller.applyConfiguration(const ScannerConfiguration(scanEnabled: true));
      expect(controller.configuration.scanDelay, 0);
      controller.applyConfiguration(const ScannerConfiguration(scanEnabled: true, scanDelay: 0x7fffffff));
      expect(controller.configuration.scanDelay, 0x7fffffff);
      expect(controller.configuration.scanEnabled, isTrue);
    });

    test('camera selection is accepted only on iOS', () {
      const configuration = ScannerConfiguration(
        iosCamera: IosCamera(position: IosCameraPosition.back, type: IosCameraType.builtInWideAngleCamera),
      );
      final original = controller.configuration;
      debugDefaultTargetPlatformOverride = TargetPlatform.android;
      expect(() => controller.applyConfiguration(configuration), throwsUnsupportedError);
      expect(controller.configuration, same(original));
      debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
      controller.applyConfiguration(configuration);
      expect(controller.configuration, same(configuration));
    });
  });

  group('visibility and lifetime', () {
    test('foreground suppresses recognition without losing the widget intent', () {
      const settings = ScannerConfiguration(scanEnabled: true, scanDelay: 100);
      controller.applyConfiguration(settings);
      controller.setForeground(false);
      expect(controller.configuration.scanEnabled, isFalse);
      expect(controller.configuration.scanDelay, 100);
      controller.setForeground(true);
      expect(controller.configuration, same(settings));
    });

    test('settings changed under a modal are restored when it closes', () {
      controller.setForeground(false);
      const settings = ScannerConfiguration(scanEnabled: true, zoomRatio: 3);
      controller.applyConfiguration(settings);
      expect(controller.configuration.scanEnabled, isFalse);
      controller.setForeground(true);
      expect(controller.configuration, same(settings));
    });

    test('disposal preserves settings and suppresses further callbacks', () {
      final scans = <Barcode>[];
      final torches = <bool>[];
      controller = harness.controller(18, onScan: scans.add, onTorchChanged: torches.add);
      final barcode = Barcode.fromJson({'raw_value': 'test', 'format': 256, 'value_type': 7});
      controller.addScanResult(barcode);
      controller.addTorchState(true);
      final state = controller.configuration;
      controller.dispose();
      controller.addScanResult(barcode);
      controller.addTorchState(false);
      controller.applyConfiguration(const ScannerConfiguration(zoomRatio: 4, scanEnabled: true));
      controller.setForeground(false);
      expect(controller.configuration, same(state));
      expect(scans, [barcode]);
      expect(torches, [true]);
    });
  });
}
