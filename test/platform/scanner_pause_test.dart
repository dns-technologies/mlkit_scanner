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
    expect(h.runtime.isCurrent(controller), isFalse);
    expect(controller.previewVisible.value, isTrue);
    await h.resumeVisible(controller);
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
    await h.resumeVisible(controller);
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
    await h.resumeVisible(controller);
    await RuntimeHarness.flush();
    expect(h.methods, ['resumeCameraMethod']);
  });

  test('pause before native startup keeps settings local until resume', () async {
    final controller = h.controller(1);
    final capture = h.runtime.capture(controller);
    await controller.pauseCamera();
    await capture;
    await controller.setZoomRatio(3);
    await RuntimeHarness.flush();
    expect(h.calls, isEmpty);
    expect(controller.previewVisible.value, isFalse);

    await h.resumeVisible(controller);
    await RuntimeHarness.flush();
    expect(h.methods, ['resumeCameraMethod']);
    expect((h.calls.single.arguments as Map)['configuration'],
        containsPair('zoomRatio', 3.0));
    expect(controller.previewVisible.value, isTrue);
  });

  for (final failed in [false, true]) {
    test('pause ignores a late capture reply (failed: $failed)', () async {
      final ack = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'resumeCameraMethod') await ack.future;
        return null;
      };
      final controller = h.controller(1);
      final capture = h.runtime.capture(controller);
      await RuntimeHarness.flush();
      await controller.pauseCamera();
      await RuntimeHarness.flush();
      if (failed) {
        ack.completeError(PlatformException(code: 'superseded'));
      } else {
        ack.complete();
      }
      await capture;
      expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
      expect(controller.previewVisible.value, isFalse);
      expect(h.errors, isEmpty);
    });

    test('late capture reply leaves resumed camera controls intact (failed: $failed)', () async {
      final ack = Completer<void>();
      var captures = 0;
      h.handler = (call) async {
        if (call.method == 'resumeCameraMethod' && ++captures == 1) await ack.future;
        return null;
      };
      final controller = h.controller(1);
      final capture = h.runtime.capture(controller);
      await RuntimeHarness.flush();
      await controller.pauseCamera();
      await RuntimeHarness.flush();
      await h.resumeVisible(controller);
      await RuntimeHarness.flush();
      expect(controller.previewVisible.value, isTrue);

      if (failed) {
        ack.completeError(PlatformException(code: 'superseded'));
      } else {
        ack.complete();
      }
      await capture;
      await controller.setZoomRatio(3);
      await controller.toggleFlash();
      await RuntimeHarness.flush();

      expect(h.runtime.isCurrent(controller), isTrue);
      expect(controller.previewVisible.value, isTrue);
      expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod', 'resumeCameraMethod', 'setZoomRatio', 'toggleFlash']);
      expect(h.errors, isEmpty);
    });
  }

  test('late capture reply cannot reveal a pending resume', () async {
    final firstAck = Completer<void>();
    final resumedAck = Completer<void>();
    var captures = 0;
    h.handler = (call) async {
      if (call.method == 'resumeCameraMethod') {
        await (++captures == 1 ? firstAck.future : resumedAck.future);
      }
      return null;
    };
    final controller = h.controller(1);
    final capture = h.runtime.capture(controller);
    await RuntimeHarness.flush();
    await controller.pauseCamera();
    await RuntimeHarness.flush();
    await h.resumeVisible(controller);
    await RuntimeHarness.flush();

    firstAck.complete();
    await capture;
    expect(controller.previewVisible.value, isFalse);
    expect(h.methods,
        ['resumeCameraMethod', 'pauseCameraMethod', 'resumeCameraMethod']);
    resumedAck.complete();
    await RuntimeHarness.flush();
    expect(controller.previewVisible.value, isTrue);
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
    await h.resumeVisible(controller);
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

  test('pause resume pause while stopping keeps the latest pause intent',
      () async {
    final controller = h.controller(1);
    await h.runtime.capture(controller);
    final stopped = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') await stopped.future;
      return null;
    };
    await controller.pauseCamera();
    expect(controller.previewVisible.value, isTrue);
    await h.resumeVisible(controller);
    await controller.pauseCamera();
    await controller.setZoomRatio(4);
    await RuntimeHarness.flush();
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);

    stopped.complete();
    await RuntimeHarness.flush();
    expect(h.runtime.isCurrent(controller), isFalse);
    expect(controller.previewVisible.value, isFalse);
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);

    await h.resumeVisible(controller);
    await RuntimeHarness.flush();
    expect(h.methods,
        ['resumeCameraMethod', 'pauseCameraMethod', 'resumeCameraMethod']);
    expect((h.calls.last.arguments as Map)['configuration'],
        containsPair('zoomRatio', 4.0));
    expect(controller.previewVisible.value, isTrue);
  });

  for (final resumeWhileStopping in [false, true]) {
    test('release during manual pause keeps resume local (pending: $resumeWhileStopping)', () async {
      final controller = h.controller(1);
      await h.runtime.capture(controller);
      final stopped = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'pauseCameraMethod') await stopped.future;
        return null;
      };
      await controller.pauseCamera();
      await RuntimeHarness.flush();
      final release = h.runtime.release(controller);
      if (resumeWhileStopping) await controller.resumeCamera();
      stopped.complete();
      await release;
      if (!resumeWhileStopping) await controller.resumeCamera();
      await RuntimeHarness.flush();

      expect(h.runtime.isCurrent(controller), isFalse);
      expect(controller.previewVisible.value, isFalse);
      expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
    });
  }

  test('takeover during manual pause does not resume the previous controller', () async {
    final first = h.controller(1);
    final second = h.controller(2);
    await h.runtime.capture(first);
    final stopped = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') await stopped.future;
      return null;
    };
    await first.pauseCamera();
    await RuntimeHarness.flush();
    final capture = h.runtime.capture(second);
    await first.resumeCamera();
    stopped.complete();
    await capture;
    await RuntimeHarness.flush();

    expect(h.methods,
        ['resumeCameraMethod', 'pauseCameraMethod', 'resumeCameraMethod']);
    expect(h.calls.last.arguments, containsPair('viewId', 2));
    expect(h.runtime.isCurrent(first), isFalse);
    expect(first.previewVisible.value, isTrue);
    expect(second.previewVisible.value, isTrue);
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
    await h.resumeVisible(controller);
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

  test('resume after failed capture restores the retained configuration', () async {
    final controller = h.controller(1);
    await controller.setZoomRatio(4);
    h.handler = (call) async {
      if (call.method == 'resumeCameraMethod') throw PlatformException(code: 'start-failed');
      return null;
    };
    await h.runtime.capture(controller);
    expect(controller.previewVisible.value, isFalse);

    h.handler = null;
    await h.resumeVisible(controller);
    await RuntimeHarness.flush();
    expect(h.methods, ['resumeCameraMethod', 'resumeCameraMethod']);
    expect((h.calls.last.arguments as Map)['configuration'], containsPair('zoomRatio', 4.0));
    expect(controller.previewVisible.value, isTrue);
  });

  test('failed pause is reported without retaining a closed session',
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
    expect(h.runtime.isCurrent(controller), isFalse);
    h.handler = null;
    await controller.pauseCamera();
    await RuntimeHarness.flush();
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
  });

  test('resume can capture again after a failed pause', () async {
    final controller = h.controller(1);
    await h.runtime.capture(controller);
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') throw PlatformException(code: 'pause-failed');
      return null;
    };
    await controller.pauseCamera();
    await RuntimeHarness.flush();
    expect(h.errors, hasLength(1));
    h.handler = null;
    await h.resumeVisible(controller);
    await RuntimeHarness.flush();
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod', 'resumeCameraMethod']);
    expect(controller.previewVisible.value, isTrue);
    expect(h.errors, hasLength(1));
  });

  test('repeated resume can capture after a shared release failure', () async {
    final controller = h.controller(1);
    await h.runtime.capture(controller);
    final stopped = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') await stopped.future;
      return null;
    };
    await controller.pauseCamera();
    await h.resumeVisible(controller);
    await RuntimeHarness.flush();
    stopped.completeError(PlatformException(code: 'pause-failed'));
    await RuntimeHarness.flush();
    expect(controller.previewVisible.value, isFalse);
    expect(h.errors, isNotEmpty);

    h.handler = null;
    await h.resumeVisible(controller);
    await RuntimeHarness.flush();
    expect(h.methods,
        ['resumeCameraMethod', 'pauseCameraMethod', 'resumeCameraMethod']);
    expect(controller.previewVisible.value, isTrue);
  });
}
