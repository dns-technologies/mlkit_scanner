import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/runtime_harness.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
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

  test('failed explicit release prevents a waiting capture and permits retry',
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
    final failure = isA<PlatformException>();
    final replies = [
      expectLater(release, throwsA(failure)),
      expectLater(capture, throwsA(failure)),
    ];
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
    await a.resumeCamera();
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
