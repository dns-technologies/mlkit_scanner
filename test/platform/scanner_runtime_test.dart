import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter/foundation.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';
import 'package:flutter_test/flutter_test.dart';
import '../support/runtime_harness.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('configuration and queue', () {
    late RuntimeHarness h;
    setUp(() => h = RuntimeHarness());
    tearDown(() => h.dispose());

    test(
        'inactive state stays local and next capture sends one complete snapshot',
        () async {
      final a = h.controller(1);
      final b = h.controller(2);
      await h.runtime.capture(a);
      await h.runtime.capture(b);
      h.calls.clear();
      await a.setZoomRatio(3);
      await a.toggleFlash();
      await a.startScan(250);
      await RuntimeHarness.flush();
      expect(h.calls, isEmpty);
      await h.runtime.capture(a);
      expect(h.methods, ['releaseCamera', 'captureCamera']);
      expect(
          (h.calls.last.arguments as Map)['configuration'],
          allOf(
            containsPair('zoomRatio', 3.0),
            containsPair('torchEnabled', true),
            containsPair('scanEnabled', true),
            containsPair('scanDelay', 250),
          ));
    });

    test('settings before capture is sent are folded into its full snapshot',
        () async {
      final a = h.controller(1);
      final capture = h.runtime.capture(a);
      final zoom = a.setZoomRatio(3);
      final torch = a.toggleFlash();
      await Future.wait([capture, zoom, torch]);
      await RuntimeHarness.flush();
      expect(h.methods, ['captureCamera']);
      expect(
          (h.calls.single.arguments as Map)['configuration'],
          allOf(
            containsPair('zoomRatio', 3.0),
            containsPair('torchEnabled', true),
          ));
    });

    test('active zoom uses one unaddressed point control without recapture',
        () async {
      final a = h.controller(17);
      await h.runtime.capture(a);
      await a.setZoomRatio(3);
      await RuntimeHarness.flush();
      expect(h.methods, ['captureCamera', 'setZoomRatio']);
      expect(h.calls.last.arguments, {'value': 3.0});
      expect(h.calls.last.arguments, isNot(contains('viewId')));
    });

    test('same values are compared to the last acknowledged configuration',
        () async {
      final a = h.controller(1);
      await h.runtime.capture(a);
      final ack = Completer<void>();
      var first = true;
      h.handler = (call) async {
        if (call.method == 'setZoomRatio' && first) {
          first = false;
          await ack.future;
        }
        return null;
      };
      await a.setZoomRatio(3);
      await a.setZoomRatio(4);
      await a.setZoomRatio(4);
      await RuntimeHarness.flush();
      expect(h.methods, ['captureCamera', 'setZoomRatio']);
      ack.complete();
      await RuntimeHarness.flush();
      expect(h.methods, ['captureCamera', 'setZoomRatio', 'setZoomRatio']);
      expect(h.calls.last.arguments, {'value': 4.0});
    });

    test('changes during capture wait for its acknowledgement', () async {
      final ack = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'captureCamera') await ack.future;
        return null;
      };
      final a = h.controller(1);
      final capture = h.runtime.capture(a);
      await RuntimeHarness.flush();
      await a.toggleFlash();
      expect(h.methods, ['captureCamera']);
      ack.complete();
      await capture;
      await RuntimeHarness.flush();
      expect(h.methods, ['captureCamera', 'toggleFlash']);
      expect(h.calls.last.arguments, {'value': true});
    });

    test(
        'takeover interrupts zoom and discards pending states before release ack',
        () async {
      final a = h.controller(1);
      final b = h.controller(2);
      await h.runtime.capture(a);
      final oldAck = Completer<void>();
      final releaseAck = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'setZoomRatio') await oldAck.future;
        if (call.method == 'releaseCamera') await releaseAck.future;
        return null;
      };
      await a.setZoomRatio(3);
      await RuntimeHarness.flush();
      await a.toggleFlash();
      final capture = h.runtime.capture(b);
      await RuntimeHarness.flush();
      expect(h.methods, ['captureCamera', 'setZoomRatio', 'releaseCamera']);
      releaseAck.complete();
      await capture;
      oldAck.completeError(PlatformException(code: 'late-error'));
      await RuntimeHarness.flush();
      expect(h.methods,
          ['captureCamera', 'setZoomRatio', 'releaseCamera', 'captureCamera']);
      expect(h.runtime.isCurrent(b), isTrue);
      expect(h.errors, isEmpty);
    });

    test(
        'rapid A B A discards intermediate capture and restores current A state',
        () async {
      final a = h.controller(1);
      final b = h.controller(2);
      await h.runtime.capture(a);
      final ack = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'releaseCamera') await ack.future;
        return null;
      };
      final skipped = h.runtime.capture(b);
      await b.setZoomRatio(5);
      final last = h.runtime.capture(a);
      await a.setZoomRatio(3);
      await skipped;
      expect(h.methods, ['captureCamera', 'releaseCamera', 'releaseCamera']);
      ack.complete();
      await last;
      expect(h.methods,
          ['captureCamera', 'releaseCamera', 'releaseCamera', 'captureCamera']);
      expect((h.calls.last.arguments as Map)['configuration'],
          containsPair('zoomRatio', 3.0));
    });

    test('failed zoom reports error and identical state can retry successfully',
        () async {
      final a = h.controller(1);
      await h.runtime.capture(a);
      h.handler = (call) async {
        if (call.method == 'setZoomRatio') {
          throw PlatformException(code: 'configure-failed');
        }
        return null;
      };
      await a.setZoomRatio(3);
      await RuntimeHarness.flush();
      expect(h.errors.single.exception, isA<PlatformException>());
      expect(a.configuration.zoomRatio, 3);
      h.handler = null;
      await a.setZoomRatio(3);
      await RuntimeHarness.flush();
      await a.setZoomRatio(3);
      await RuntimeHarness.flush();
      expect(h.methods,
          ['captureCamera', 'setZoomRatio', 'releaseCamera', 'captureCamera']);
    });

    test(
        'each field uses its point control and unchanged values are not resent',
        () async {
      final a = h.controller(1, scanning: true);
      await h.runtime.capture(a);
      h.calls.clear();
      await a.setZoomRatio(3);
      await a.toggleFlash();
      await a.setCropArea(const CropRect(scaleWidth: 0.5));
      await a.setDelay(250);
      await RuntimeHarness.flush();
      expect(h.methods,
          ['setZoomRatio', 'toggleFlash', 'setCropArea', 'setScanDelay']);
      expect(h.calls.map((c) => c.arguments), [
        {'value': 3.0},
        {'value': true},
        {'cropRect': const CropRect(scaleWidth: 0.5).toJson()},
        {'delay': 250},
      ]);
      h.calls.clear();
      await a.setZoomRatio(3);
      await a.setCropArea(const CropRect(scaleWidth: 0.5));
      await a.startScan(250);
      await RuntimeHarness.flush();
      expect(h.calls, isEmpty);
    });

    test('failed recovery capture reports its error after replacing the queue',
        () async {
      final a = h.controller(1);
      await h.runtime.capture(a);
      h.handler = (call) async {
        if (call.method == 'setZoomRatio' || call.method == 'captureCamera') {
          throw PlatformException(code: '${call.method}-failed');
        }
        return null;
      };
      await a.setZoomRatio(3);
      await RuntimeHarness.flush();
      await a.toggleFlash();
      await RuntimeHarness.flush();
      expect(h.errors.map((e) => (e.exception as PlatformException).code),
          ['setZoomRatio-failed', 'captureCamera-failed']);
      expect(h.methods,
          ['captureCamera', 'setZoomRatio', 'releaseCamera', 'captureCamera']);
      h.handler = null;
      await a.setDelay(100);
      await RuntimeHarness.flush();
      expect(h.calls.last.method, 'captureCamera');
      expect(
          (h.calls.last.arguments as Map)['configuration'],
          allOf(
            containsPair('zoomRatio', 3.0),
            containsPair('torchEnabled', true),
            containsPair('scanDelay', 100),
          ));
    });

    test('late capture acknowledgement cannot overwrite a replacement snapshot',
        () async {
      final a = h.controller(1);
      final b = h.controller(2);
      final oldCapture = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'captureCamera' &&
            (call.arguments as Map)['viewId'] == 1) {
          await oldCapture.future;
        }
        return null;
      };
      final first = h.runtime.capture(a);
      await RuntimeHarness.flush();
      await b.setZoomRatio(4);
      await h.runtime.capture(b);
      await first;
      oldCapture.complete();
      await RuntimeHarness.flush();
      h.calls.clear();
      await b.setZoomRatio(4);
      await RuntimeHarness.flush();
      expect(h.calls, isEmpty);
      expect(h.runtime.isCurrent(b), isTrue);
      await a.setZoomRatio(9);
      await RuntimeHarness.flush();
      expect(h.calls, isEmpty);
    });

    test(
        'acknowledgement retains the sent snapshot rather than a newer controller state',
        () async {
      final a = h.controller(1);
      await h.runtime.capture(a);
      final zoomAck = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'setZoomRatio') await zoomAck.future;
        return null;
      };
      h.calls.clear();
      await a.setZoomRatio(3);
      await RuntimeHarness.flush();
      await a.toggleFlash();
      expect(a.configuration.torchEnabled, isTrue);
      expect(h.methods, ['setZoomRatio']);
      zoomAck.complete();
      await RuntimeHarness.flush();
      expect(h.methods, ['setZoomRatio', 'toggleFlash']);
      expect(h.calls.last.arguments, {'value': true});
    });

    test(
        'partial point failure restores all fields including a reverted successful field',
        () async {
      final a = h.controller(1);
      await h.runtime.capture(a);
      h.handler = (call) async {
        if (call.method == 'toggleFlash') {
          throw PlatformException(code: 'torch-failed');
        }
        return null;
      };
      await a.setZoomRatio(3);
      await a.toggleFlash();
      await RuntimeHarness.flush();
      expect(h.errors, hasLength(1));
      h.calls.clear();
      h.handler = null;
      await a.setZoomRatio(1);
      await RuntimeHarness.flush();
      expect(h.methods, ['releaseCamera', 'captureCamera']);
      expect(
          (h.calls.last.arguments as Map)['configuration'],
          allOf(
            containsPair('zoomRatio', 1.0),
            containsPair('torchEnabled', true),
          ));
    });

    test(
        'a failed point control restores the next full snapshot before preview',
        () async {
      final a = h.controller(1, scanning: true);
      await h.runtime.capture(a);
      h.calls.clear();
      h.handler = (call) async {
        if (call.method == 'setZoomRatio') {
          throw PlatformException(code: 'zoom-failed');
        }
        return null;
      };
      await a.setZoomRatio(3);
      await a.toggleFlash();
      await RuntimeHarness.flush();
      expect(h.methods, ['setZoomRatio', 'releaseCamera', 'captureCamera']);
      expect(
          (h.calls.last.arguments as Map)['configuration'],
          allOf(
            containsPair('zoomRatio', 3.0),
            containsPair('torchEnabled', true),
          ));
      expect(h.errors, hasLength(1));
      h.calls.clear();
      h.handler = null;
      await a.setZoomRatio(3);
      await RuntimeHarness.flush();
      expect(h.calls, isEmpty);
      await a.setZoomRatio(3);
      await RuntimeHarness.flush();
      expect(h.calls, isEmpty);
    });

    test(
        'changing physical iOS camera recaptures with the complete current configuration',
        () async {
      debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
      addTearDown(() => debugDefaultTargetPlatformOverride = null);
      final a = h.controller(1, scanning: true);
      await h.runtime.capture(a);
      await a.setZoomRatio(3);
      await a.toggleFlash();
      await RuntimeHarness.flush();
      h.calls.clear();
      await a.setIosCamera(
          position: IosCameraPosition.front,
          type: IosCameraType.builtInWideAngleCamera);
      await RuntimeHarness.flush();
      expect(h.methods, ['releaseCamera', 'captureCamera']);
      expect(
          (h.calls.last.arguments as Map)['configuration'],
          allOf(
            containsPair('zoomRatio', 3.0),
            containsPair('torchEnabled', true),
            containsPair('scanEnabled', true),
            containsPair('iosCamera', {'position': 2, 'type': 0}),
          ));
      h.calls.clear();
      await a.setIosCamera(
          position: IosCameraPosition.front,
          type: IosCameraType.builtInWideAngleCamera);
      await RuntimeHarness.flush();
      expect(h.calls, isEmpty);
    });

    test(
        'takeover during physical camera release never sends the old recapture',
        () async {
      debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
      addTearDown(() => debugDefaultTargetPlatformOverride = null);
      final a = h.controller(1);
      final b = h.controller(2);
      await h.runtime.capture(a);
      final releaseAck = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'releaseCamera') await releaseAck.future;
        return null;
      };
      await a.setIosCamera(
          position: IosCameraPosition.front,
          type: IosCameraType.builtInWideAngleCamera);
      await RuntimeHarness.flush();
      final next = h.runtime.capture(b);
      releaseAck.complete();
      await next;
      await RuntimeHarness.flush();
      expect(h.methods,
          ['captureCamera', 'releaseCamera', 'releaseCamera', 'captureCamera']);
      expect(h.calls.last.arguments, containsPair('viewId', 2));
    });

    test('failed release prevents capture and retry uses retained state',
        () async {
      final a = h.controller(1);
      final b = h.controller(2);
      await h.runtime.capture(a);
      h.handler = (call) async {
        if (call.method == 'releaseCamera') {
          throw PlatformException(code: 'release-failed');
        }
        return null;
      };
      await expectLater(
          h.runtime.capture(b), throwsA(isA<PlatformException>()));
      await b.setZoomRatio(3);
      await RuntimeHarness.flush();
      expect(h.methods, ['captureCamera', 'releaseCamera', 'releaseCamera']);
      expect(h.errors, hasLength(1));
      h.handler = null;
      await h.runtime.capture(b);
      await RuntimeHarness.flush();
      expect(h.methods, [
        'captureCamera',
        'releaseCamera',
        'releaseCamera',
        'releaseCamera',
        'captureCamera'
      ]);
    });

    test(
        'new desired state retries a failed capture without a false pause state',
        () async {
      final a = h.controller(1);
      h.handler = (call) async {
        if (call.method == 'captureCamera') {
          throw PlatformException(code: 'capture-failed');
        }
        return null;
      };
      await expectLater(
          h.runtime.capture(a), throwsA(isA<PlatformException>()));
      h.handler = null;
      await a.setZoomRatio(3);
      await RuntimeHarness.flush();
      expect(h.methods, ['captureCamera', 'releaseCamera', 'captureCamera']);
      h.calls.clear();
      await a.setZoomRatio(3);
      await RuntimeHarness.flush();
      expect(h.calls, isEmpty);
      await h.runtime.capture(a);
      expect((h.calls.last.arguments as Map)['configuration'],
          containsPair('zoomRatio', 3.0));
    });
  });

  group('events', () {
    late RuntimeHarness h;
    setUp(() => h = RuntimeHarness());
    tearDown(() => h.dispose());

    test(
        'native events go only to the selected controller without Dart address filtering',
        () async {
      final a = h.controller(1, scanning: true);
      final b = h.controller(2, scanning: true);
      final resultsA = <String?>[];
      final resultsB = <String?>[];
      final torchA = <bool>[];
      addTearDown(a.scanResults.listen((v) => resultsA.add(v.rawValue)).cancel);
      addTearDown(b.scanResults.listen((v) => resultsB.add(v.rawValue)).cancel);
      addTearDown(a.torchToggleStream.listen(torchA.add).cancel);
      await h.runtime.capture(a);
      await h.event(2, 'foreign');
      await h.event(1, 'a');
      await h.runtime.capture(b);
      await h.event(1, 'old');
      await h.event(2, 'b');
      await h.runtime.capture(a);
      await h.event(1, 'new-a');
      expect(resultsA, ['foreign', 'a', 'new-a']);
      expect(resultsB, ['old', 'b']);
      expect(torchA, [true, true, true]);
    });

    test(
        'native events are forwarded without waiting for capture acknowledgement',
        () async {
      final ready = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'captureCamera') await ready.future;
        return null;
      };
      final a = h.controller(1, scanning: true);
      final scans = <String?>[];
      final torch = <bool>[];
      addTearDown(a.scanResults.listen((v) => scans.add(v.rawValue)).cancel);
      addTearDown(a.torchToggleStream.listen(torch.add).cancel);
      final capture = h.runtime.capture(a);
      await RuntimeHarness.flush();
      await h.event(1, 'not-ready');
      expect(scans, ['not-ready']);
      expect(torch, [true]);
      ready.complete();
      await capture;
      await h.event(1, 'ready');
      expect(scans, ['not-ready', 'ready']);
      expect(torch, [true, true]);
    });

    test('scan cancellation is queued behind zoom without a Dart event gate',
        () async {
      final a = h.controller(1, scanning: true);
      final scans = <String?>[];
      addTearDown(a.scanResults.listen((v) => scans.add(v.rawValue)).cancel);
      await h.runtime.capture(a);
      final zoomReady = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'setZoomRatio' &&
            (call.arguments as Map)['value'] == 3) {
          await zoomReady.future;
        }
        return null;
      };
      final zoom = a.setZoomRatio(3);
      await RuntimeHarness.flush();
      final cancel = a.cancelScan();
      await h.event(1, 'cancelled');
      expect(h.methods.where((m) => m == 'setZoomRatio'), hasLength(1));
      expect(scans, ['cancelled']);
      zoomReady.complete();
      await Future.wait([zoom, cancel]);
      await a.startScan(0);
      await RuntimeHarness.flush();
      await h.event(1, 'current-run');
      expect(scans, ['cancelled', 'current-run']);
      expect(h.calls.last.arguments, isNot(contains('viewId')));
      expect(h.calls.last.method, 'startScan');
    });

    test('pending point controls do not interrupt scan or torch delivery',
        () async {
      final a = h.controller(17, scanning: true);
      final scans = <String?>[];
      final torch = <bool>[];
      addTearDown(a.scanResults.listen((v) => scans.add(v.rawValue)).cancel);
      addTearDown(a.torchToggleStream.listen(torch.add).cancel);
      await h.runtime.capture(a);
      final ack = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'setZoomRatio') await ack.future;
        return null;
      };
      await a.setZoomRatio(3);
      await RuntimeHarness.flush();
      await h.event(17, 'during-zoom');
      expect(scans, ['during-zoom']);
      expect(torch, [true]);
      ack.complete();
      await RuntimeHarness.flush();
    });

    test('cancel then restart stay ordered while native owns scan delivery',
        () async {
      final a = h.controller(1, scanning: true);
      final scans = <String?>[];
      addTearDown(a.scanResults.listen((v) => scans.add(v.rawValue)).cancel);
      await h.runtime.capture(a);
      final zoomAck = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'setZoomRatio') await zoomAck.future;
        return null;
      };
      await a.setZoomRatio(3);
      await RuntimeHarness.flush();
      await a.cancelScan();
      await a.startScan(0);
      await h.event(1, 'old-run');
      expect(scans, ['old-run']);
      zoomAck.complete();
      await RuntimeHarness.flush();
      expect(h.methods,
          ['captureCamera', 'setZoomRatio', 'cancelScan', 'startScan']);
      await h.event(1, 'new-run');
      expect(scans, ['old-run', 'new-run']);
    });

    test('late failed point control cannot remove the new active session',
        () async {
      final a = h.controller(1, scanning: true);
      final b = h.controller(2, scanning: true);
      final scans = <String?>[];
      addTearDown(b.scanResults.listen((v) => scans.add(v.rawValue)).cancel);
      await h.runtime.capture(a);
      final oldAck = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'setZoomRatio') await oldAck.future;
        return null;
      };
      await a.setZoomRatio(3);
      await RuntimeHarness.flush();
      await h.runtime.capture(b);
      oldAck.completeError(PlatformException(code: 'late-error'));
      await RuntimeHarness.flush();
      await h.event(2, 'current');
      expect(scans, ['current']);
      expect(h.errors, isEmpty);
    });

    test(
        'unchanged configuration of a ready camera neither interrupts events nor recaptures',
        () async {
      final a = h.controller(1, scanning: true);
      final scans = <String?>[];
      addTearDown(a.scanResults.listen((v) => scans.add(v.rawValue)).cancel);
      await h.runtime.capture(a);
      h.calls.clear();
      await a.setZoomRatio(a.configuration.zoomRatio);
      await RuntimeHarness.flush();
      expect(h.calls, isEmpty);
      await h.event(1, 'still-running');
      expect(scans, ['still-running']);
    });

    test('runtime trusts native events even when desired scanning is disabled',
        () async {
      final a = h.controller(1);
      final scans = <String?>[];
      final torch = <bool>[];
      addTearDown(a.scanResults.listen((v) => scans.add(v.rawValue)).cancel);
      addTearDown(a.torchToggleStream.listen(torch.add).cancel);
      await h.runtime.capture(a);
      await h.event(1, 'disabled');
      expect(scans, ['disabled']);
      expect(torch, [true]);
    });

    test('release invalidates events even when native release fails', () async {
      final a = h.controller(1, scanning: true);
      final scans = <String?>[];
      final torch = <bool>[];
      addTearDown(a.scanResults.listen((v) => scans.add(v.rawValue)).cancel);
      addTearDown(a.torchToggleStream.listen(torch.add).cancel);
      await h.runtime.capture(a);
      h.handler = (call) async {
        if (call.method == 'releaseCamera') {
          throw PlatformException(code: 'release-failed');
        }
        return null;
      };
      final release =
          expectLater(h.runtime.release(a), throwsA(isA<PlatformException>()));
      await h.event(1, 'released');
      await release;
      expect(h.errors, isEmpty);
      expect(scans, isEmpty);
      expect(torch, isEmpty);
    });

    test('late capture acknowledgement does not change native event forwarding',
        () async {
      final oldReply = Completer<void>();
      final newReply = Completer<void>();
      var count = 0;
      h.handler = (call) async {
        if (call.method == 'captureCamera') {
          await (++count == 1 ? oldReply.future : newReply.future);
        }
        return null;
      };
      final a = h.controller(1, scanning: true);
      final scans = <String?>[];
      addTearDown(a.scanResults.listen((v) => scans.add(v.rawValue)).cancel);
      final old = h.runtime.capture(a);
      await RuntimeHarness.flush();
      final next = h.runtime.capture(a);
      await old;
      await RuntimeHarness.flush();
      oldReply.complete();
      await h.event(1, 'late-old');
      await h.event(1, 'not-ready');
      expect(scans, ['late-old', 'not-ready']);
      newReply.complete();
      await next;
      await h.event(1, 'current');
      expect(scans, ['late-old', 'not-ready', 'current']);
    });

    test('malformed or untagged results are ignored', () async {
      final a = h.controller(1, scanning: true);
      final scans = <String?>[];
      addTearDown(a.scanResults.listen((v) => scans.add(v.rawValue)).cancel);
      await h.runtime.capture(a);
      await h.send(const MethodCall('onScanResult', {
        'viewId': 1,
        'barcode': {'raw_value': 'untagged'}
      }));
      await h.send(
          const MethodCall('onScanResult', {'viewId': 'bad', 'barcode': {}}));
      expect(scans, isEmpty);
    });

    test(
        'runtime does not delay native scan events until start acknowledgement',
        () async {
      final a = h.controller(17);
      final scans = <String?>[];
      final torch = <bool>[];
      addTearDown(a.scanResults.listen((v) => scans.add(v.rawValue)).cancel);
      addTearDown(a.torchToggleStream.listen(torch.add).cancel);
      await h.runtime.capture(a);
      final ack = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'startScan') await ack.future;
        return null;
      };
      final start = a.startScan(0);
      await h.event(17, 'not-ready');
      expect(scans, ['not-ready']);
      expect(torch, [true]);
      ack.complete();
      await start;
      await h.event(17, 'ready');
      expect(scans, ['not-ready', 'ready']);
      await h.runtime.unregister(a);
      await h.event(17, 'unregistered');
      expect(scans, ['not-ready', 'ready']);
      expect(torch, [true, true]);
    });

    test('failed native scan cancellation retries the desired configuration',
        () async {
      final a = h.controller(1, scanning: true);
      final scans = <String?>[];
      addTearDown(a.scanResults.listen((v) => scans.add(v.rawValue)).cancel);
      await h.runtime.capture(a);
      h.handler = (call) async {
        if (call.method == 'cancelScan') {
          throw PlatformException(code: 'cancel-failed');
        }
        return null;
      };
      await a.cancelScan();
      await h.event(1, 'cancelled');
      expect(scans, ['cancelled']);
      await a.toggleFlash();
      await RuntimeHarness.flush();
      expect(h.errors, hasLength(1));
      expect(h.methods.where((m) => m == 'cancelScan'), hasLength(1));
      expect(h.methods,
          ['captureCamera', 'cancelScan', 'releaseCamera', 'captureCamera']);
      expect((h.calls.last.arguments as Map)['configuration'],
          containsPair('scanEnabled', false));
      await h.event(1, 'still-cancelled');
      expect(scans, ['cancelled', 'still-cancelled']);
    });
  });

  group('lifecycle', () {
    // WidgetTester supplies a fake clock; these tests do not mount any widgets.
    void runtimeTest(
        String name, Future<void> Function(RuntimeHarness, WidgetTester) body) {
      testWidgets(name, (tester) async {
        final h = RuntimeHarness();
        try {
          await body(h, tester);
        } finally {
          await h.dispose();
        }
      });
    }

    runtimeTest('registration alone never allocates or disposes the scanner',
        (h, tester) async {
      final a = h.controller(1);
      await a.setZoomRatio(3);
      await tester.pump(const Duration(seconds: 1));
      await h.runtime.unregister(a);
      await tester.pump(const Duration(seconds: 1));
      expect(h.calls, isEmpty);
    });

    runtimeTest('release is immediate; Dart sends no delayed cleanup commands',
        (h, tester) async {
      final a = h.controller(1);
      h.controller(
          2); // Registered background views do not keep the camera alive.
      await h.runtime.capture(a);
      await h.runtime.release(a);
      expect(h.methods, ['captureCamera', 'releaseCamera']);
      await tester.pump(const Duration(milliseconds: 299));
      expect(h.methods, ['captureCamera', 'releaseCamera']);
      await tester.pump(const Duration(milliseconds: 1));
      expect(h.methods, ['captureCamera', 'releaseCamera']);
      expect(h.calls.last.arguments, isNull);
      await tester.pump(const Duration(seconds: 1));
      expect(h.methods, ['captureCamera', 'releaseCamera']);
    });

    runtimeTest(
        'capture sends retained state without waiting for an idle deadline',
        (h, tester) async {
      final a = h.controller(1);
      final b = h.controller(2);
      await h.runtime.capture(a);
      await h.runtime.release(a);
      await tester.pump(const Duration(milliseconds: 299));
      await b.setZoomRatio(4);
      await h.runtime.capture(b);
      await tester.pump(const Duration(seconds: 1));
      expect(h.methods, ['captureCamera', 'releaseCamera', 'captureCamera']);
      expect((h.calls.last.arguments as Map)['configuration'],
          containsPair('zoomRatio', 4.0));
      expect(h.runtime.isCurrent(b), isTrue);
    });

    runtimeTest('inactive setters and unregister send no extra native commands',
        (h, tester) async {
      final a = h.controller(1);
      final b = h.controller(2);
      await h.runtime.capture(a);
      await h.runtime.release(a);
      await tester.pump(const Duration(milliseconds: 200));
      await a.setZoomRatio(3);
      await b.toggleFlash();
      await h.runtime.unregister(b);
      await h.runtime.release(a);
      await tester.pump(const Duration(milliseconds: 100));
      expect(h.methods, ['captureCamera', 'releaseCamera']);
    });

    runtimeTest(
        'release preserves configuration; capture reapplies the complete latest state',
        (h, tester) async {
      final a = h.controller(1, scanning: true);
      await h.runtime.capture(a);
      await h.runtime.release(a);
      await tester.pump(const Duration(milliseconds: 200));
      await a.setZoomRatio(3);
      await a.setDelay(400);
      await a.toggleFlash();
      await h.runtime.release(a);
      await tester.pump(const Duration(milliseconds: 100));
      expect(h.methods, ['captureCamera', 'releaseCamera']);
      await h.runtime.capture(a);
      expect(h.calls.last.method, 'captureCamera');
      expect(
          (h.calls.last.arguments as Map)['configuration'],
          allOf(
            containsPair('zoomRatio', 3.0),
            containsPair('scanDelay', 400),
            containsPair('scanEnabled', true),
            containsPair('torchEnabled', true),
            isNot(contains('cameraEnabled')),
          ));
    });

    runtimeTest('unfinished release does not trigger extra cleanup calls',
        (h, tester) async {
      final a = h.controller(1);
      await h.runtime.capture(a);
      final ack = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'releaseCamera') await ack.future;
        return null;
      };
      final release = h.runtime.release(a);
      await tester.pump(const Duration(milliseconds: 300));
      expect(h.methods, ['captureCamera', 'releaseCamera']);
      ack.complete();
      await release;
      await tester.pump();
      expect(h.methods, ['captureCamera', 'releaseCamera']);
    });

    runtimeTest(
        'capture after explicit release does not retain its acknowledgement',
        (h, tester) async {
      final a = h.controller(1);
      final b = h.controller(2);
      await h.runtime.capture(a);
      final ack = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'releaseCamera') await ack.future;
        return null;
      };
      final release = h.runtime.release(a);
      await tester.pump(const Duration(milliseconds: 300));
      final capture = h.runtime.capture(b);
      await tester.pump();
      expect(h.methods, ['captureCamera', 'releaseCamera', 'captureCamera']);
      expect(h.runtime.isCurrent(b), isTrue);
      ack.complete();
      await Future.wait([release, capture]);
      await tester.pump(const Duration(seconds: 1));
      expect(h.methods, ['captureCamera', 'releaseCamera', 'captureCamera']);
    });

    runtimeTest(
        'a failed release clears selection without scheduling Dart cleanup',
        (h, tester) async {
      final a = h.controller(1);
      await h.runtime.capture(a);
      h.handler = (call) async {
        if (call.method == 'releaseCamera') {
          throw PlatformException(code: 'release-failed');
        }
        return null;
      };
      await expectLater(
          h.runtime.release(a), throwsA(isA<PlatformException>()));
      await tester.pump(const Duration(milliseconds: 300));
      expect(h.methods, ['captureCamera', 'releaseCamera']);
    });

    runtimeTest('unregister detaches listener and prevents future captures',
        (h, tester) async {
      final a = h.controller(1);
      h.runtime.register(a); // Registration is idempotent.
      await h.runtime.capture(a);
      await a.toggleFlash();
      await tester.pump();
      expect(h.methods.where((m) => m == 'toggleFlash'), hasLength(1));
      await h.runtime.unregister(a);
      await a.setZoomRatio(5);
      await h.runtime.capture(a);
      await tester.pump(const Duration(milliseconds: 300));
      expect(h.methods, ['captureCamera', 'toggleFlash', 'releaseCamera']);
      expect(h.runtime.isCurrent(a), isFalse);
      expect(a.configuration.zoomRatio, 5);
      h.runtime.register(a);
      await h.runtime.capture(a);
      expect((h.calls.last.arguments as Map)['configuration'],
          containsPair('zoomRatio', 5.0));
    });

    runtimeTest('unregistered controllers cannot allocate the scanner',
        (h, tester) async {
      final a = BarcodeScannerController(viewId: 7);
      await h.runtime.unregister(a);
      await h.runtime.capture(a);
      await h.runtime.release(a);
      a.dispose();
      await tester.pump(const Duration(seconds: 1));
      expect(h.calls, isEmpty);
    });

    runtimeTest(
        'controller constructor registers and dispose unregisters itself',
        (h, tester) async {
      final a = BarcodeScannerController(viewId: 17);
      await a.setZoomRatio(3);
      expect(h.calls, isEmpty);
      await h.runtime.capture(a);
      expect(h.runtime.isCurrent(a), isTrue);
      a.dispose();
      a.dispose();
      expect(h.runtime.isCurrent(a), isFalse);
      await tester.pump(const Duration(milliseconds: 300));
      await a.toggleFlash();
      await h.runtime.capture(a);
      expect(h.methods, ['captureCamera', 'releaseCamera']);
    });

    runtimeTest('disposing a replaced controller cannot release the new owner',
        (h, tester) async {
      final a = h.controller(1);
      final b = h.controller(2);
      await h.runtime.capture(a);
      await h.runtime.capture(b);
      h.calls.clear();
      a.dispose();
      await tester.pump(const Duration(seconds: 1));
      expect(h.runtime.isCurrent(b), isTrue);
      expect(h.calls, isEmpty);
      await b.toggleFlash();
      await tester.pump();
      expect(h.methods, ['toggleFlash']);
    });
  });
}
