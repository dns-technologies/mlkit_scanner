import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/crop_rect.dart';

import '../support/runtime_harness.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late RuntimeHarness h;
  setUp(() => h = RuntimeHarness());
  tearDown(() => h.dispose());

  test('pause retains settings and resume restores one complete snapshot',
      () async {
    final controller = h.controller(1, scanning: true);
    await h.runtime.capture(controller);
    await controller.pauseCamera();
    await controller.setZoomRatio(3);
    await controller.toggleFlash();
    await controller.setCropArea(const CropRect(scaleWidth: 0.5));
    await controller.setDelay(250);
    await controller.pauseCamera();
    await RuntimeHarness.flush();
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
    expect(h.runtime.isCurrent(controller), isTrue);
    await controller.resumeCamera();
    await RuntimeHarness.flush();
    expect(h.methods,
        ['resumeCameraMethod', 'pauseCameraMethod', 'resumeCameraMethod']);
    expect(
        (h.calls.last.arguments as Map)['configuration'],
        allOf(
          containsPair('zoomRatio', 3.0),
          containsPair('torchEnabled', true),
          containsPair('scanEnabled', true),
          containsPair('scanDelay', 250),
          containsPair('cropRect', const CropRect(scaleWidth: 0.5).toJson()),
          isNot(contains('cameraPaused')),
        ));
    await controller.resumeCamera();
    await RuntimeHarness.flush();
    expect(h.calls, hasLength(3));
  });

  test('inactive pause and resume cannot change another controller camera',
      () async {
    final first = h.controller(1);
    final second = h.controller(2);
    await h.runtime.capture(second);
    await first.pauseCamera();
    await first.resumeCamera();
    await RuntimeHarness.flush();
    expect(h.methods, ['resumeCameraMethod']);
    expect(h.runtime.isCurrent(second), isTrue);
  });

  test('pause survives release and a later visible capture', () async {
    final controller = h.controller(1);
    await h.runtime.capture(controller);
    await controller.pauseCamera();
    await RuntimeHarness.flush();
    await h.runtime.release(controller);
    h.calls.clear();
    await h.runtime.capture(controller);
    expect(h.calls, isEmpty);
    await controller.resumeCamera();
    await RuntimeHarness.flush();
    expect(h.methods, ['resumeCameraMethod']);
  });

  test('pause interrupts pending capture and rejects its late failure',
      () async {
    final ack = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'resumeCameraMethod') await ack.future;
      return null;
    };
    final controller = h.controller(1);
    final capture = h.runtime.capture(controller);
    await RuntimeHarness.flush();
    await controller.pauseCamera();
    await capture;
    ack.completeError(PlatformException(code: 'superseded'));
    await RuntimeHarness.flush();
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
    expect(h.errors, isEmpty);
  });

  test('resume waits for pause and folds settings supplied while waiting',
      () async {
    final controller = h.controller(1);
    await h.runtime.capture(controller);
    final ack = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') await ack.future;
      return null;
    };
    await controller.pauseCamera();
    await controller.resumeCamera();
    await controller.setZoomRatio(4);
    await RuntimeHarness.flush();
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
    ack.complete();
    await RuntimeHarness.flush();
    expect(h.methods,
        ['resumeCameraMethod', 'pauseCameraMethod', 'resumeCameraMethod']);
    expect((h.calls.last.arguments as Map)['configuration'],
        containsPair('zoomRatio', 4.0));
  });

  test('pause interrupts pending control without applying queued settings',
      () async {
    final controller = h.controller(1);
    await h.runtime.capture(controller);
    final ack = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'setZoomRatio') await ack.future;
      return null;
    };
    await controller.setZoomRatio(3);
    await RuntimeHarness.flush();
    await controller.toggleFlash();
    await controller.pauseCamera();
    ack.complete();
    await RuntimeHarness.flush();
    expect(h.methods, ['resumeCameraMethod', 'setZoomRatio', 'pauseCameraMethod']);
  });

  test(
      'paused controller receives no events and can cancel retained recognition',
      () async {
    final controller = h.controller(1, scanning: true);
    final scans = <String?>[];
    addTearDown(controller.scanResults
        .listen((value) => scans.add(value.rawValue))
        .cancel);
    await h.runtime.capture(controller);
    await controller.pauseCamera();
    await h.event(1, 'stale');
    expect(scans, isEmpty);
    await controller.cancelScan();
    await controller.resumeCamera();
    await RuntimeHarness.flush();
    expect((h.calls.last.arguments as Map)['configuration'],
        containsPair('scanEnabled', false));
  });

  test('disposed controllers ignore pause and resume', () async {
    final controller = h.controller(1);
    controller.dispose();
    await controller.pauseCamera();
    await controller.resumeCamera();
    expect(h.calls, isEmpty);
  });

  test('resume during a paused capture starts the camera only once', () async {
    final controller = h.controller(1);
    await controller.pauseCamera();
    final capture = h.runtime.capture(controller);
    await controller.resumeCamera();
    await capture;
    await RuntimeHarness.flush();
    expect(h.methods, ['resumeCameraMethod']);
  });

  test('failed pause is reported and a repeated pause retries native shutdown',
      () async {
    final controller = h.controller(1);
    await h.runtime.capture(controller);
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') {
        throw PlatformException(code: 'pause-failed');
      }
      return null;
    };
    await controller.pauseCamera();
    await RuntimeHarness.flush();
    expect(h.errors.single.exception, isA<PlatformException>());
    h.handler = null;
    await controller.pauseCamera();
    await RuntimeHarness.flush();
    expect(
        h.methods, ['resumeCameraMethod', 'pauseCameraMethod', 'pauseCameraMethod']);
  });
}
