import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/runtime_harness.dart';
import '../support/immediate_frame_test_binding.dart';

void main() {
  ImmediateFrameTestBinding();
  late RuntimeHarness h;
  setUp(() => h = RuntimeHarness());
  tearDown(() => h.dispose());

  test('capture after explicit release waits for native cancellation',
      () async {
    final a = h.controller(1);
    final b = h.controller(2);
    await h.runtime.capture(a);
    final stopped = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') await stopped.future;
      return null;
    };
    final release = h.runtime.release(a);
    final capture = h.runtime.capture(b);
    await b.setZoomRatio(4);
    await RuntimeHarness.flush();
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
    stopped.complete();
    await Future.wait([release, capture]);
    expect(h.methods,
        ['resumeCameraMethod', 'pauseCameraMethod', 'resumeCameraMethod']);
    expect((h.calls.last.arguments as Map)['configuration'],
        containsPair('zoomRatio', 4.0));
  });

  test('rapid captures share pending release and only the latest starts', () async {
    final a = h.controller(1);
    final b = h.controller(2);
    final c = h.controller(3);
    await h.runtime.capture(a);
    final stopped = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') await stopped.future;
      return null;
    };
    final skipped = h.runtime.capture(b);
    final capture = h.runtime.capture(c);
    await c.setZoomRatio(4);
    await RuntimeHarness.flush();
    expect(h.runtime.isCurrent(b), isFalse);
    expect(h.runtime.isCurrent(c), isTrue);
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
    stopped.complete();
    await Future.wait([skipped, capture]);
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod', 'resumeCameraMethod']);
    expect(h.calls.last.arguments, containsPair('viewId', 3));
    expect((h.calls.last.arguments as Map)['configuration'], containsPair('zoomRatio', 4.0));
    expect(b.previewVisible.value, isFalse);
    expect(c.previewVisible.value, isTrue);
  });

  test('selected capture waits for release before starting the camera', () async {
    final a = h.controller(1);
    final b = h.controller(2);
    await h.runtime.capture(a);
    final stopped = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') await stopped.future;
      return null;
    };
    final capture = h.runtime.capture(b);
    await b.setZoomRatio(4);
    await RuntimeHarness.flush();
    expect(h.runtime.isCurrent(b), isTrue);
    expect(b.previewVisible.value, isFalse);
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
    stopped.complete();
    await capture;
    expect(h.runtime.isCurrent(b), isTrue);
    expect((h.calls.last.arguments as Map)['configuration'], containsPair('zoomRatio', 4.0));
  });

  test('a controller unregistered during release cannot start a new session', () async {
    final a = h.controller(1);
    final b = h.controller(2);
    await h.runtime.capture(a);
    final stopped = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') await stopped.future;
      return null;
    };
    final capture = h.runtime.capture(b);
    await h.runtime.unregister(b);
    stopped.complete();
    await capture;
    expect(h.runtime.isCurrent(b), isFalse);
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
  });

  test('an immediate repeated release reply cannot bypass the original cancellation', () async {
    final a = h.controller(1);
    final b = h.controller(2);
    await h.runtime.capture(a);
    final stopped = Completer<void>();
    var releases = 0;
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod' && ++releases == 1) await stopped.future;
      return null;
    };
    final release = h.runtime.release(a);
    final capture = h.runtime.capture(b);
    await RuntimeHarness.flush();
    final prematureCaptures = h.calls.where((call) => call.method == 'resumeCameraMethod').length;
    stopped.complete();
    await Future.wait([release, capture]);
    expect(prematureCaptures, 1);
    expect(h.runtime.isCurrent(b), isTrue);
  });

  test('release of a waiting capture preserves the previous native cancellation', () async {
    final a = h.controller(1);
    final b = h.controller(2);
    final c = h.controller(3);
    await h.runtime.capture(a);
    final stopped = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') await stopped.future;
      return null;
    };
    final firstRelease = h.runtime.release(a);
    expect(h.runtime.isCurrent(a), isFalse);
    final skipped = h.runtime.capture(b);
    final secondRelease = h.runtime.release(b);
    expect(h.runtime.isCurrent(b), isFalse);
    final capture = h.runtime.capture(c);
    await RuntimeHarness.flush();
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
    stopped.complete();
    await Future.wait([firstRelease, skipped, secondRelease, capture]);
    expect(h.runtime.isCurrent(c), isTrue);
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod', 'resumeCameraMethod']);
    expect(h.calls.last.arguments, containsPair('viewId', 3));
  });

  test('release cancels a capture already waiting for another camera to stop', () async {
    final a = h.controller(1);
    final b = h.controller(2);
    await h.runtime.capture(a);
    final stopped = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') await stopped.future;
      return null;
    };
    final capture = h.runtime.capture(b);
    await RuntimeHarness.flush();
    final release = h.runtime.release(b);
    stopped.complete();
    await Future.wait([capture, release]);

    expect(h.runtime.isCurrent(b), isFalse);
    expect(b.previewVisible.value, isFalse);
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
  });

  test('a later capture supersedes a request already awaiting release', () async {
    final a = h.controller(1);
    final b = h.controller(2);
    final c = h.controller(3);
    await h.runtime.capture(a);
    final stopped = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') await stopped.future;
      return null;
    };
    final skipped = h.runtime.capture(b);
    await RuntimeHarness.flush();
    final capture = h.runtime.capture(c);
    stopped.complete();
    await Future.wait([skipped, capture]);

    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod', 'resumeCameraMethod']);
    expect(h.calls.last.arguments, containsPair('viewId', 3));
    expect(b.previewVisible.value, isFalse);
    expect(c.previewVisible.value, isTrue);
  });

  test('repeated release shares cancellation and leaves settings local', () async {
    final a = h.controller(1);
    await h.runtime.capture(a);
    final stopped = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') await stopped.future;
      return null;
    };
    final release = h.runtime.release(a);
    var repeatedCompleted = false;
    final repeated = h.runtime.release(a).then((_) {
      repeatedCompleted = true;
    });
    await a.setZoomRatio(4);
    await RuntimeHarness.flush();
    expect(h.runtime.isCurrent(a), isFalse);
    expect(repeatedCompleted, isFalse);
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
    stopped.complete();
    await Future.wait([release, repeated]);
    expect(h.runtime.isCurrent(a), isFalse);
    await h.runtime.capture(a);
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod', 'resumeCameraMethod']);
    expect((h.calls.last.arguments as Map)['configuration'], containsPair('zoomRatio', 4.0));
  });

  for (final captured in [false, true]) {
    test('inactive release does not disable camera controls (captured: $captured)', () async {
      final a = h.controller(1);
      final b = h.controller(2);
      if (captured) await h.runtime.capture(a);
      await h.runtime.release(b);
      if (!captured) await h.runtime.capture(a);

      await a.setZoomRatio(3);
      await a.toggleFlash();
      await RuntimeHarness.flush();
      expect(h.runtime.isCurrent(a), isTrue);
      expect(h.methods, ['resumeCameraMethod', 'setZoomRatio', 'toggleFlash']);
      expect(h.calls[1].arguments, {'value': 3.0});
      expect(h.calls[2].arguments, {'value': true});
      expect(h.errors, isEmpty);
    });
  }

  test('concurrent releases share a failure without retaining the released session', () async {
    final a = h.controller(1);
    final b = h.controller(2);
    await h.runtime.capture(a);
    final stopping = Completer<void>();
    final stopped = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') {
        stopping.complete();
        await stopped.future;
      }
      return null;
    };
    final first = h.runtime.release(a);
    final repeated = h.runtime.release(a);
    final inactive = h.runtime.release(b);
    final replies = [
      for (final release in [first, repeated, inactive]) expectLater(release, throwsA(isA<PlatformException>())),
    ];
    await stopping.future;
    stopped.completeError(PlatformException(code: 'stop-failed'));
    await Future.wait(replies);
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
    expect(h.errors, isEmpty);
    h.handler = null;
    await h.runtime.release(a);
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
    expect(h.runtime.isCurrent(a), isFalse);
    await h.runtime.capture(b);
    expect(h.calls.last.arguments, containsPair('viewId', 2));
  });

  test('a preview listener can join the release that hides it', () async {
    final a = h.controller(1);
    await h.runtime.capture(a);
    final stopped = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') await stopped.future;
      return null;
    };
    late Future<void> repeated;
    var repeatedCompleted = false;
    void onPreviewChanged() {
      if (!a.previewVisible.value) {
        repeated = h.runtime.release(a).then((_) {
          repeatedCompleted = true;
        });
      }
    }

    a.previewVisible.addListener(onPreviewChanged);
    addTearDown(() => a.previewVisible.removeListener(onPreviewChanged));
    final release = h.runtime.release(a);
    await RuntimeHarness.flush();
    expect(repeatedCompleted, isFalse);
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
    stopped.complete();
    await Future.wait([release, repeated]);
    expect(repeatedCompleted, isTrue);
    expect(h.runtime.isCurrent(a), isFalse);
  });

  test('capture from its own preview listener supersedes a recapture', () async {
    final a = h.controller(1);
    final c = h.controller(3);
    await h.runtime.capture(a);
    final stopped = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') await stopped.future;
      return null;
    };
    late Future<void> capture;
    void onPreviewChanged() {
      if (!a.previewVisible.value) capture = h.runtime.capture(c);
    }

    a.previewVisible.addListener(onPreviewChanged);
    addTearDown(() => a.previewVisible.removeListener(onPreviewChanged));
    final skipped = h.runtime.capture(a);
    await RuntimeHarness.flush();
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
    stopped.complete();
    await Future.wait([skipped, capture]);
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod', 'resumeCameraMethod']);
    expect(h.runtime.isCurrent(c), isTrue);
    expect(h.calls.last.arguments, containsPair('viewId', 3));
    expect(a.previewVisible.value, isFalse);
  });

  test('captures before native startup do not send a redundant release', () async {
    final a = h.controller(1);
    final b = h.controller(2);
    final skipped = h.runtime.capture(a);
    final capture = h.runtime.capture(b);
    await Future.wait([skipped, capture]);
    expect(h.methods, ['resumeCameraMethod']);
    expect(h.calls.single.arguments, containsPair('viewId', 2));
  });

  test('failure of a shared release reaches all waiting captures and permits retry', () async {
    final a = h.controller(1);
    final b = h.controller(2);
    final c = h.controller(3);
    await h.runtime.capture(a);
    final stopped = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') await stopped.future;
      return null;
    };
    final skipped = h.runtime.capture(b);
    final capture = h.runtime.capture(c);
    final skippedFailure = expectLater(skipped, throwsA(isA<PlatformException>()));
    final failure = expectLater(capture, throwsA(isA<PlatformException>()));
    await RuntimeHarness.flush();
    stopped.completeError(PlatformException(code: 'stop-failed'));
    await Future.wait([skippedFailure, failure]);
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
    expect(c.previewVisible.value, isFalse);
    expect(h.errors, isEmpty);
    h.handler = null;
    await h.runtime.capture(c);
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod', 'resumeCameraMethod']);
    expect(h.calls.last.arguments, containsPair('viewId', 3));
    expect(c.previewVisible.value, isTrue);
  });

  test('failed explicit release prevents a waiting capture and permits retry',
      () async {
    final a = h.controller(1);
    final b = h.controller(2);
    await h.runtime.capture(a);
    final stopping = Completer<void>();
    final stopped = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') {
        stopping.complete();
        await stopped.future;
      }
      return null;
    };
    final release = h.runtime.release(a);
    final capture = h.runtime.capture(b);
    final failure = isA<PlatformException>();
    final replies = [
      expectLater(release, throwsA(failure)),
      expectLater(capture, throwsA(failure)),
    ];
    await stopping.future;
    stopped.completeError(PlatformException(code: 'stop-failed'));
    await Future.wait(replies);
    expect(h.methods, ['resumeCameraMethod', 'pauseCameraMethod']);
    h.handler = null;
    await h.runtime.capture(b);
    expect(h.calls.last.arguments, containsPair('viewId', 2));
  });

  test('same view events during release cannot enter the next capture',
      () async {
    final a = h.controller(1, scanning: true);
    final scans = <String?>[];
    final torch = <bool>[];
    addTearDown(
        a.scanResults.listen((value) => scans.add(value.rawValue)).cancel);
    addTearDown(a.torchToggleStream.listen(torch.add).cancel);
    await h.runtime.capture(a);
    final stopped = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') await stopped.future;
      return null;
    };
    final capture = h.runtime.capture(a);
    await h.event(1, 'old-run');
    expect(scans, isEmpty);
    expect(torch, isEmpty);
    stopped.complete();
    await capture;
    await h.event(1, 'new-run');
    expect(scans, ['new-run']);
    expect(torch, [true]);
  });

  test('resume does not forward old events before pending pause completes',
      () async {
    final a = h.controller(1, scanning: true);
    final scans = <String?>[];
    addTearDown(
        a.scanResults.listen((value) => scans.add(value.rawValue)).cancel);
    await h.runtime.capture(a);
    final stopped = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'pauseCameraMethod') await stopped.future;
      return null;
    };
    await a.pauseCamera();
    await h.resumeVisible(a);
    await h.event(1, 'old-run');
    expect(scans, isEmpty);
    stopped.complete();
    await RuntimeHarness.flush();
    await h.event(1, 'new-run');
    expect(scans, ['new-run']);
  });

  test('failed startup cannot forward native events', () async {
    final a = h.controller(1, scanning: true);
    final scans = <String?>[];
    addTearDown(
        a.scanResults.listen((value) => scans.add(value.rawValue)).cancel);
    h.handler = (call) async {
      if (call.method == 'resumeCameraMethod') {
        throw PlatformException(code: 'start-failed');
      }
      return null;
    };
    await expectLater(h.runtime.capture(a), throwsA(isA<PlatformException>()));
    await h.event(1, 'failed-start');
    expect(scans, isEmpty);
  });

  test('controller can be disposed by its scan listener', () async {
    final a = h.controller(1, scanning: true);
    var scans = 0;
    a.scanResults.listen((_) {
      scans++;
      a.dispose();
    });
    await h.runtime.capture(a);
    await h.event(1, 'done');
    await h.event(1, 'late');
    expect(scans, 1);
    expect(h.errors, isEmpty);
    expect(h.runtime.isCurrent(a), isFalse);
  });
}
