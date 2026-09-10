import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/crop_rect.dart';

import '../support/runtime_harness.dart';
import '../support/immediate_frame_test_binding.dart';

void main() {
  ImmediateFrameTestBinding();
  late RuntimeHarness h;
  setUp(() => h = RuntimeHarness());
  tearDown(() => h.dispose());

  test('a preview listener can dispose its own controller', () async {
    final controller = h.controller(1);
    controller.previewVisible.addListener(controller.dispose);
    await h.runtime.capture(controller);
    await RuntimeHarness.flush();
    expect(h.runtime.isCurrent(controller), isFalse);
    expect(controller.previewVisible.value, isFalse);
    expect(h.errors, isEmpty);
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
  });

  test('preview waits for capture and the latest crop arriving during queue drain', () async {
    final captured = Completer<void>();
    final zoomed = Completer<void>();
    final cropped = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'resumeCameraMethod') await captured.future;
      if (call.method == 'setZoomRatio') await zoomed.future;
      if (call.method == 'setCropAreaMethod') await cropped.future;
      return null;
    };
    final controller = h.controller(1, scanning: true);
    final visibility = <bool>[];
    controller.previewVisible.addListener(() => visibility.add(controller.previewVisible.value));
    final scans = <String?>[];
    addTearDown(controller.scanResults.listen((value) => scans.add(value.rawValue)).cancel);
    expect(controller.previewVisible.value, isFalse);
    final capture = h.runtime.capture(controller);
    await RuntimeHarness.flush();
    await controller.setZoomRatio(2);
    await controller.setCropArea(const CropRect(scaleWidth: 0.5));
    await controller.setZoomRatio(3);
    expect(h.methods, ['resumeCameraMethod']);

    captured.complete();
    await RuntimeHarness.flush();
    expect(h.methods, ['resumeCameraMethod', 'setZoomRatio']);
    expect(h.calls.last.arguments, {'value': 3.0});
    expect(controller.previewVisible.value, isFalse);
    await controller.setCropArea(const CropRect(scaleWidth: 0.8));
    zoomed.complete();
    await RuntimeHarness.flush();
    expect(h.methods, ['resumeCameraMethod', 'setZoomRatio', 'setCropAreaMethod']);
    expect(h.calls.last.arguments, {'cropRect': const CropRect(scaleWidth: 0.8).toJson()});
    expect(controller.previewVisible.value, isFalse);
    await h.event(1, 'old-crop');
    expect(scans, isEmpty);

    cropped.complete();
    await capture;
    expect(visibility, [true]);
    await h.event(1, 'ready');
    expect(scans, ['ready']);
  });

  test('replacement capture discards old pending work and alone reveals the preview', () async {
    final controller = h.controller(1);
    await h.runtime.capture(controller);
    final oldZoom = Completer<void>();
    final newCapture = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'setZoomRatio') await oldZoom.future;
      if (call.method == 'resumeCameraMethod') await newCapture.future;
      return null;
    };
    await controller.setZoomRatio(2);
    await controller.setCropArea(const CropRect(scaleWidth: 0.5));
    final capture = h.runtime.capture(controller);
    expect(controller.previewVisible.value, isFalse);
    await RuntimeHarness.flush();
    expect(h.calls.last.arguments, containsPair('configuration', controller.configuration.toJson()));
    oldZoom.complete();
    await RuntimeHarness.flush();
    expect(controller.previewVisible.value, isFalse);
    expect(h.methods.where((method) => method == 'setCropAreaMethod'), isEmpty);
    newCapture.complete();
    await capture;
    expect(controller.previewVisible.value, isTrue);
  });

  test('takeover preserves the old preview and release hides only its controller', () async {
    final first = h.controller(1);
    final second = h.controller(2);
    await h.runtime.capture(first);
    final captured = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'resumeCameraMethod') await captured.future;
      return null;
    };
    final capture = h.runtime.capture(second);
    expect(first.previewVisible.value, isTrue);
    expect(second.previewVisible.value, isFalse);
    captured.complete();
    await capture;
    expect(first.previewVisible.value, isTrue);
    expect(second.previewVisible.value, isTrue);
    final release = h.runtime.release(second);
    expect(second.previewVisible.value, isFalse);
    await release;
    expect(first.previewVisible.value, isTrue);
  });

  test('failed queued crop keeps the cover until a later configuration recovers', () async {
    final captured = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'resumeCameraMethod') await captured.future;
      if (call.method == 'setCropAreaMethod') throw PlatformException(code: 'crop-failed');
      return null;
    };
    final controller = h.controller(1);
    final capture = h.runtime.capture(controller);
    await RuntimeHarness.flush();
    await controller.setCropArea(const CropRect(scaleWidth: 0.5));
    await controller.setCropArea(const CropRect(scaleWidth: 0.6));
    captured.complete();
    await capture;
    expect(controller.previewVisible.value, isFalse);
    expect(h.errors, hasLength(1));
    expect(h.methods, ['resumeCameraMethod', 'setCropAreaMethod']);
    h.handler = null;
    await controller.setCropArea(const CropRect(scaleWidth: 0.6));
    await RuntimeHarness.flush();
    expect(h.calls.last.arguments, containsPair('configuration', controller.configuration.toJson()));
    expect(controller.previewVisible.value, isTrue);
  });

  test('a failed capture drops its queue without exposing the preview', () async {
    final captured = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'resumeCameraMethod') await captured.future;
      return null;
    };
    final controller = h.controller(1);
    final capture = expectLater(h.runtime.capture(controller), throwsA(isA<PlatformException>()));
    await RuntimeHarness.flush();
    await controller.setCropArea(const CropRect(scaleWidth: 0.5));
    await controller.setZoomRatio(3);
    captured.completeError(PlatformException(code: 'capture-failed'));
    await capture;
    expect(controller.previewVisible.value, isFalse);
    expect(h.methods, ['resumeCameraMethod']);
    expect(h.errors, isEmpty);
  });

  test('scan start uses the latest delay and a later delay waits for its acknowledgement', () async {
    final captured = Completer<void>();
    final started = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'resumeCameraMethod') await captured.future;
      if (call.method == 'startScan') await started.future;
      return null;
    };
    final controller = h.controller(1);
    final capture = h.runtime.capture(controller);
    await RuntimeHarness.flush();
    await controller.startScan(100);
    await controller.cancelScan();
    await controller.setDelay(200);
    await controller.startScan(300);
    captured.complete();
    await RuntimeHarness.flush();
    expect(h.methods, ['resumeCameraMethod', 'startScan']);
    expect(h.calls.last.arguments, containsPair('delay', 300));
    await controller.setDelay(400);
    expect(controller.previewVisible.value, isFalse);
    started.complete();
    await capture;
    expect(h.methods, ['resumeCameraMethod', 'startScan', 'setScanDelay']);
    expect(h.calls.last.arguments, {'delay': 400});
    expect(controller.previewVisible.value, isTrue);
  });
}
