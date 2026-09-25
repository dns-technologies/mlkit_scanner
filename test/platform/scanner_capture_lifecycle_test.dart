import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/runtime_harness.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late RuntimeHarness h;
  setUp(() => h = RuntimeHarness());
  tearDown(() => h.dispose());

  test('capture acknowledges readiness after settings changed during startup finish', () async {
    final controller = h.controller(1);
    await controller.configure(zoomRatio: 2);
    final started = Completer<void>();
    final settings = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'resumeCameraMethod') await started.future;
      if (call.method == 'updateCameraSettings') await settings.future;
      return null;
    };
    var completed = false;
    final capture = h.runtime.capture(controller).then((_) => completed = true);
    await RuntimeHarness.flush();
    final activation = h.calls.singleWhere((call) => call.method == 'resumeCameraMethod');
    expect((activation.arguments as Map)['configuration'], containsPair('zoomRatio', 2.0));

    await controller.configure(zoomRatio: 4);
    started.complete();
    await RuntimeHarness.flush();
    final update = h.calls.singleWhere((call) => call.method == 'updateCameraSettings');
    expect(update.arguments, {'captureId': h.leases[1], 'zoomRatio': 4.0});
    expect(completed, isFalse);
    expect(controller.previewVisible.value, isFalse);

    settings.complete();
    await capture;
    expect(controller.previewVisible.value, isTrue);
  });

  for (final replace in [false, true]) {
    test('late startup settings cannot restore a revoked capture (replace=$replace)', () async {
      final first = h.controller(1);
      final second = h.controller(2);
      final started = Completer<void>();
      final settings = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'resumeCameraMethod' && (call.arguments as Map)['captureId'] == h.leases[1]) {
          await started.future;
        }
        if (call.method == 'updateCameraSettings') await settings.future;
        return null;
      };
      final capture = h.runtime.capture(first);
      await RuntimeHarness.flush();
      await first.configure(zoomRatio: 4);
      started.complete();
      await RuntimeHarness.flush();
      expect(h.methods, contains('updateCameraSettings'));

      if (replace) {
        await h.runtime.capture(second);
      } else {
        await h.runtime.release(first);
      }
      await capture;
      settings.completeError(PlatformException(code: 'obsolete-settings'));
      await RuntimeHarness.flush();

      expect(first.previewVisible.value, isFalse);
      expect(h.runtime.isCurrent(first), isFalse);
      expect(h.runtime.isCurrent(second), replace);
      expect(second.previewVisible.value, replace);
      expect(h.errors, isEmpty);
    });
  }

  for (final cancelWaiting in [false, true]) {
    for (final stopFails in [false, true]) {
      test('latest capture waits for physical release (cancel waiting=$cancelWaiting, stop fails=$stopFails)', () async {
        final first = h.controller(1);
        final waiting = h.controller(2);
        final latest = h.controller(3);
        await h.runtime.capture(first);
        final stopped = Completer<void>();
        h.handler = (call) async {
          if (call.method == 'pauseCameraMethod') await stopped.future;
          return null;
        };
        final release = h.runtime.release(first);
        final releaseResult = expectLater(release, stopFails ? throwsA(isA<PlatformException>()) : completes);
        final skipped = h.runtime.capture(waiting);
        await RuntimeHarness.flush();
        final canceled = cancelWaiting ? h.runtime.release(waiting) : Future<void>.value();
        final capture = h.runtime.capture(latest);
        await latest.configure(zoomRatio: 4);
        await RuntimeHarness.flush();
        final beforeStop = h.calls.where((call) => call.method == 'resumeCameraMethod').toList();
        final selectedWhileStopping = h.runtime.isCurrent(latest);

        if (stopFails) {
          stopped.completeError(PlatformException(code: 'stop-failed'));
        } else {
          stopped.complete();
        }
        await Future.wait([releaseResult, skipped, canceled, capture]);

        expect(beforeStop, hasLength(1), reason: 'The replacement must not activate before the old hardware pause completes.');
        expect(selectedWhileStopping, isTrue);
        expect(h.leases, isNot(contains(2)));
        final activations = h.calls.where((call) => call.method == 'resumeCameraMethod').toList();
        expect(activations, hasLength(2));
        expect(activations.last.arguments, containsPair('captureId', h.leases[3]));
        expect((activations.last.arguments as Map)['configuration'], containsPair('zoomRatio', 4.0));
        expect(waiting.previewVisible.value, isFalse);
        expect(latest.previewVisible.value, isTrue);
        expect(h.errors, isEmpty);
      });
    }
  }
}
