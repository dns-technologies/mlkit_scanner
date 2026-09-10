import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/crop_rect.dart';
import 'package:mlkit_scanner/models/ios_camera_position.dart';
import 'package:mlkit_scanner/models/ios_camera_type.dart';
import 'package:mlkit_scanner/platform/scanner_configuration.dart';
import 'package:mlkit_scanner/platform/scanner_controller.dart';
import 'package:mlkit_scanner/platform/ml_kit_channel.dart';
import 'package:mlkit_scanner/platform/scanner_runtime.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late BarcodeScannerController controller;
  setUp(() {
    ScannerRuntime.instance = ScannerRuntime(MlKitChannel());
    controller = BarcodeScannerController(viewId: 17);
  });
  tearDown(() async {
    controller.dispose();
    debugDefaultTargetPlatformOverride = null;
  });

  test('is bound at construction and updates state without an active session',
      () async {
    expect(controller.viewId, 17);
    await controller.setZoomRatio(3);
    await controller.toggleFlash();
    await controller.startScan(250);
    expect(controller.configuration.zoomRatio, 3);
    expect(controller.configuration.torchEnabled, isTrue);
    expect(controller.configuration.scanEnabled, isTrue);
    expect(controller.configuration.scanDelay, 250);
  });

  test('stores a new immutable full snapshot before notifying listeners',
      () async {
    const crop = CropRect(scaleWidth: 0.6, scaleHeight: 0.4);
    final initial = controller.configuration;
    final states = <ScannerConfiguration>[];
    controller.states.listen((state) async {
      expect(identical(controller.configuration, state), isTrue);
      states.add(state);
    });
    await controller.setZoomRatio(3);
    await controller.toggleFlash();
    await controller.setCropArea(crop);
    await controller.startScan(100);
    await controller.cancelScan();
    expect(initial.zoomRatio, 1);
    expect(initial.torchEnabled, isFalse);
    expect(states, hasLength(5));
    expect(states.first.zoomRatio, 3);
    expect(states.first.torchEnabled, isFalse);
    expect(states.last.zoomRatio, 3);
    expect(states.last.torchEnabled, isTrue);
    expect(states.last.cropRect, crop);
    expect(states.last.scanEnabled, isFalse);
    expect(states.last.scanDelay, 100);
  });

  test('broadcast subscriptions receive state independently', () async {
    final first = <ScannerConfiguration>[];
    final second = <ScannerConfiguration>[];
    final one = controller.states.listen(first.add);
    final two = controller.states.listen(second.add);
    await controller.toggleFlash();
    await one.cancel();
    await controller.setZoomRatio(3);
    expect(first, hasLength(1));
    expect(second, hasLength(2));
    expect(second.last.zoomRatio, 3);
    await two.cancel();
  });

  test('removing a listener prevents further state notifications', () async {
    var count = 0;
    Future<void> listener(ScannerConfiguration _) async => count++;
    final subscription = controller.states.listen(listener);
    await controller.toggleFlash();
    await subscription.cancel();
    await controller.toggleFlash();
    expect(count, 1);
    expect(controller.configuration.torchEnabled, isFalse);
  });

  test('invalid values neither mutate state nor emit a snapshot', () async {
    final original = controller.configuration;
    var count = 0;
    controller.states.listen((_) async => count++);
    for (final delay in [-1, 0x80000000]) {
      await expectLater(controller.startScan(delay), throwsRangeError);
      await expectLater(controller.setDelay(delay), throwsRangeError);
    }
    for (final zoom in [0.0, -1.0, double.nan, double.infinity]) {
      await expectLater(controller.setZoomRatio(zoom), throwsArgumentError);
    }
    for (final crop in [
      const CropRect(scaleWidth: 0, scaleHeight: 1),
      const CropRect(scaleWidth: 1, scaleHeight: -1),
      const CropRect(scaleWidth: double.nan, scaleHeight: 1),
      const CropRect(scaleWidth: 1, scaleHeight: double.infinity),
      const CropRect(scaleWidth: 1, scaleHeight: 1, offsetX: double.nan),
      const CropRect(scaleWidth: 1, scaleHeight: 1, offsetY: double.infinity),
    ]) {
      await expectLater(controller.setCropArea(crop), throwsArgumentError);
    }
    expect(identical(controller.configuration, original), isTrue);
    expect(count, 0);
  });

  test('delay boundary values are accepted in Dart', () async {
    await controller.startScan(0);
    expect(controller.configuration.scanDelay, 0);
    await controller.setDelay(0x7fffffff);
    expect(controller.configuration.scanDelay, 0x7fffffff);
    expect(controller.configuration.scanEnabled, isTrue);
  });

  test('iOS selection emits state; unsupported platform leaves state intact',
      () async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    final original = controller.configuration;
    await expectLater(
      controller.setIosCamera(
        position: IosCameraPosition.back,
        type: IosCameraType.builtInWideAngleCamera,
      ),
      throwsUnsupportedError,
    );
    expect(identical(controller.configuration, original), isTrue);
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
    final states = <ScannerConfiguration>[];
    controller.states.listen((state) async => states.add(state));
    await controller.setIosCamera(
      position: IosCameraPosition.back,
      type: IosCameraType.builtInWideAngleCamera,
    );
    expect(states.single.iosCamera?.position, IosCameraPosition.back);
    expect(states.single.iosCamera?.type, IosCameraType.builtInWideAngleCamera);
  });

  test('disposal stops emissions, preserves state and closes result streams',
      () async {
    var count = 0;
    controller.states.listen((_) async => count++);
    final scansDone = controller.scanResults.drain<void>();
    final torchDone = controller.torchToggleStream.drain<void>();
    final state = controller.configuration;
    controller.dispose();
    await Future.wait([scansDone, torchDone]);
    await controller.setZoomRatio(4);
    await controller.toggleFlash();
    await controller.startScan(100);
    await controller.cancelScan();
    await controller.setDelay(100);
    expect(identical(controller.configuration, state), isTrue);
    expect(count, 0);
  });
}
