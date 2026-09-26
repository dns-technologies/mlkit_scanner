import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/barcode.dart';
import 'package:mlkit_scanner/models/crop_rect.dart';
import 'package:mlkit_scanner/platform/ml_kit_channel.dart';
import 'package:mlkit_scanner/platform/scanner_preview.dart';
import 'package:mlkit_scanner/platform/scanner_runtime.dart';
import 'package:mlkit_scanner/src/platform/scanner_controller.dart';
import 'package:mlkit_scanner/utils/mlkit_utils.dart';
import '../support/runtime_harness.dart';

void main() {
  group('configuration', () {
    TestWidgetsFlutterBinding.ensureInitialized();
    late RuntimeHarness h;
    setUp(() => h = RuntimeHarness());
    tearDown(() => h.dispose());

    test('one configuration update batches changed camera settings in one native call', () async {
      final a = h.controller(1);
      await h.runtime.capture(a);
      h.calls.clear();

      await a.configure(zoomRatio: 3, torchEnabled: true, cropRect: const CropRect(scaleWidth: .5));
      await RuntimeHarness.flush();

      expect(h.methods, ['updateCameraSettings']);
      expect(h.calls.single.arguments, {
        'captureId': h.leases[1],
        'zoomRatio': 3.0,
        'torchEnabled': true,
        'cropRect': const CropRect(scaleWidth: .5).toJson(),
      });
      h.calls.clear();
      await a.configure(zoomRatio: 3, torchEnabled: true, cropRect: const CropRect(scaleWidth: .5));
      await RuntimeHarness.flush();
      expect(h.calls, isEmpty);
    });

    test('inactive configuration is restored by a single complete capture snapshot', () async {
      final a = h.controller(1);
      final b = h.controller(2);
      await h.runtime.capture(a);
      await h.runtime.capture(b);
      h.calls.clear();
      await a.configure(zoomRatio: 3);
      await a.configure(torchEnabled: !a.configuration.torchEnabled);
      await a.configure(scanEnabled: true, scanDelay: 250);
      await RuntimeHarness.flush();
      expect(h.calls, isEmpty);
      await h.runtime.capture(a);
      final resume = h.calls.singleWhere((c) => c.method == 'resumeCameraMethod');
      expect(resume.arguments, containsPair('captureId', h.leases[1]));
      expect((resume.arguments as Map).keys, isNot(contains('viewId')));
      expect((resume.arguments as Map)['configuration'], allOf(containsPair('zoomRatio', 3.0), containsPair('torchEnabled', true)));
      expect(h.calls.singleWhere((call) => call.method == 'startScan').arguments, containsPair('delay', 250));
      expect(h.methods, isNot(contains('pauseCameraMethod')));
    });

    test('changes before startup are folded into its full snapshot', () async {
      final a = h.controller(1);
      final capture = h.runtime.capture(a);
      await a.configure(zoomRatio: 3);
      await a.configure(torchEnabled: !a.configuration.torchEnabled);
      await capture;
      final resume = h.calls.singleWhere((c) => c.method == 'resumeCameraMethod');
      expect((resume.arguments as Map)['configuration'], containsPair('zoomRatio', 3.0));
      expect(h.methods, isNot(contains('updateCameraSettings')));
    });

    test('active controls are scoped to the concrete lease without recapture', () async {
      final a = h.controller(1);
      await h.runtime.capture(a);
      h.calls.clear();
      await a.configure(zoomRatio: 3);
      await RuntimeHarness.flush();
      expect(h.methods, ['updateCameraSettings']);
      expect(h.calls.single.arguments, {'zoomRatio': 3.0, 'captureId': h.leases[1]});
    });

    test('busy control coalesces intermediate states and applies the latest value', () async {
      final a = h.controller(1);
      await h.runtime.capture(a);
      h.calls.clear();
      final ack = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'updateCameraSettings' && (call.arguments as Map)['zoomRatio'] == 2) {
          await ack.future;
        }
        return null;
      };
      await a.configure(zoomRatio: 2);
      await a.configure(zoomRatio: 3);
      await a.configure(zoomRatio: 4);
      await RuntimeHarness.flush();
      expect(h.methods, ['updateCameraSettings']);
      ack.complete();
      await RuntimeHarness.flush();
      expect(h.calls.map((c) => (c.arguments as Map)['zoomRatio']), [2.0, 4.0]);
    });

    test('pending batch reconciles every changed field against its acknowledged snapshot', () async {
      final a = h.controller(1);
      await h.runtime.capture(a);
      h.calls.clear();
      final ack = Completer<void>();
      addTearDown(() {
        if (!ack.isCompleted) ack.complete();
      });
      h.handler = (call) async {
        if (call.method == 'updateCameraSettings') await ack.future;
        return null;
      };
      await a.configure(zoomRatio: 2, torchEnabled: true);
      await a.configure(zoomRatio: 3, torchEnabled: true);
      await a.configure(zoomRatio: 1, torchEnabled: false);
      await RuntimeHarness.flush();
      expect(h.methods, ['updateCameraSettings']);
      ack.complete();
      await RuntimeHarness.flush();
      expect(h.methods, ['updateCameraSettings', 'updateCameraSettings']);
      expect(h.calls.map((call) => call.arguments), [
        {'captureId': h.leases[1], 'zoomRatio': 2.0, 'torchEnabled': true},
        {'captureId': h.leases[1], 'zoomRatio': 1.0, 'torchEnabled': false},
      ]);
    });

    test('returning to acknowledged value corrects an unfinished point control', () async {
      final a = h.controller(1);
      await h.runtime.capture(a);
      h.calls.clear();
      final ack = Completer<void>();
      h.handler = (call) async {
        if ((call.arguments as Map?)?['zoomRatio'] == 3) await ack.future;
        return null;
      };
      await a.configure(zoomRatio: 3);
      await RuntimeHarness.flush();
      await a.configure(zoomRatio: 1);
      ack.complete();
      await RuntimeHarness.flush();
      expect(h.calls.map((c) => (c.arguments as Map)['zoomRatio']), [3.0, 1.0]);
    });

    test('startup reconciles configuration changed while native camera opens', () async {
      final ack = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'resumeCameraMethod') await ack.future;
        return null;
      };
      final a = h.controller(1);
      final capture = h.runtime.capture(a);
      await RuntimeHarness.flush();
      await a.configure(torchEnabled: !a.configuration.torchEnabled);
      await a.configure(cropRect: const CropRect(scaleWidth: .5));
      expect(h.methods, isNot(contains('updateCameraSettings')));
      ack.complete();
      await capture;
      expect(h.methods, containsAllInOrder(['resumeCameraMethod', 'updateCameraSettings']));
      expect(h.calls.singleWhere((call) => call.method == 'updateCameraSettings').arguments, {
        'captureId': h.leases[1],
        'torchEnabled': true,
        'cropRect': const CropRect(scaleWidth: .5).toJson(),
      });
    });

    test('one failed reconciliation reports once and a later update can recover', () async {
      final a = h.controller(1);
      await h.runtime.capture(a);
      final ack = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'updateCameraSettings') await ack.future;
        return null;
      };
      await a.configure(zoomRatio: 2);
      await a.configure(zoomRatio: 3);
      await a.configure(torchEnabled: !a.configuration.torchEnabled);
      ack.completeError(PlatformException(code: 'zoom-failed'));
      await RuntimeHarness.flush();
      expect(h.errors, hasLength(1));
      h.handler = null;
      await a.configure(zoomRatio: 4);
      await RuntimeHarness.flush();
      expect(
        h.calls.lastWhere((c) => c.method == 'resumeCameraMethod').arguments,
        containsPair('configuration', a.configuration.toCaptureArguments()),
      );
    });

    test('disabled recognition retains delay in Dart and applies it once when started', () async {
      final a = h.controller(1);
      await h.runtime.capture(a);
      h.calls.clear();
      await a.configure(scanDelay: 100);
      await a.configure(scanDelay: 250);
      await RuntimeHarness.flush();
      expect(h.calls, isEmpty);

      await a.configure(scanEnabled: true, scanDelay: a.configuration.scanDelay);
      await RuntimeHarness.flush();
      expect(h.methods, ['subscribeScan', 'startScan']);
      expect(h.calls.last.arguments, containsPair('delay', 250));

      h.calls.clear();
      await a.configure(scanDelay: 500);
      await RuntimeHarness.flush();
      expect(h.methods, ['setScanDelay']);
      expect(h.calls.single.arguments, containsPair('delay', 500));
    });

    test('delay changed during scan startup is reconciled after its acknowledgment', () async {
      final a = h.controller(1);
      await h.runtime.capture(a);
      h.calls.clear();
      final started = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'startScan') await started.future;
        return null;
      };
      await a.configure(scanEnabled: true, scanDelay: 100);
      await RuntimeHarness.flush();
      await a.configure(scanDelay: 250);
      await RuntimeHarness.flush();
      expect(h.methods, ['subscribeScan', 'startScan']);
      started.complete();
      await RuntimeHarness.flush();
      expect(h.methods, ['subscribeScan', 'startScan', 'setScanDelay']);
      expect(h.calls[1].arguments, containsPair('delay', 100));
      expect(h.calls.last.arguments, containsPair('delay', 250));
    });

    test('starting recognition during a Dart-only update is not lost at drain completion', () async {
      final a = h.controller(1);
      await h.runtime.capture(a);
      h.calls.clear();
      final delay = a.configure(scanDelay: 100);
      final start = a.configure(scanEnabled: true, scanDelay: 250);
      await Future.wait([delay, start]);
      await RuntimeHarness.flush();
      expect(h.methods, ['subscribeScan', 'startScan']);
      expect(h.calls.last.arguments, containsPair('delay', 250));
    });

    test('settings arriving around the initial activation acknowledgment are always applied', () async {
      for (var microtasks = 0; microtasks <= 24; microtasks++) {
        final a = h.controller(microtasks + 1);
        h.calls.clear();
        void updateAfter(int remaining) {
          if (remaining == 0) {
            unawaited(a.configure(zoomRatio: 2));
          } else {
            scheduleMicrotask(() => updateAfter(remaining - 1));
          }
        }

        h.handler = (call) async {
          if (call.method == 'resumeCameraMethod') updateAfter(microtasks);
          return null;
        };
        await h.runtime.capture(a);
        await RuntimeHarness.flush();
        final zooms = h.calls.where((call) => call.method == 'resumeCameraMethod' || call.method == 'updateCameraSettings').map((call) {
          final arguments = call.arguments as Map;
          return call.method == 'updateCameraSettings' ? arguments['zoomRatio'] : (arguments['configuration'] as Map)['zoomRatio'];
        });
        expect(zooms.last, 2.0, reason: 'Update delayed by $microtasks microtasks');
      }
    });
  });

  group('ownership races', () {
    TestWidgetsFlutterBinding.ensureInitialized();
    late RuntimeHarness h;
    setUp(() => h = RuntimeHarness());
    tearDown(() => h.dispose());

    test('handoff bypasses unfinished old controls and suppresses late errors', () async {
      final a = h.controller(1);
      final b = h.controller(2);
      await h.runtime.capture(a);
      final old = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'updateCameraSettings') await old.future;
        return null;
      };
      await a.configure(zoomRatio: 2);
      await a.configure(torchEnabled: !a.configuration.torchEnabled);
      await h.runtime.capture(b);
      expect(h.runtime.isCurrent(b), isTrue);
      expect(h.methods, isNot(contains('pauseCameraMethod')));
      old.completeError(PlatformException(code: 'old'));
      await RuntimeHarness.flush();
      expect(h.errors, isEmpty);
      expect(h.calls.where((call) => call.method == 'updateCameraSettings'), hasLength(1));
    });

    test('rapid A B A starts only the latest desired owner', () async {
      final a = h.controller(1);
      final b = h.controller(2);
      await h.runtime.capture(a);
      h.calls.clear();
      await Future.wait([h.runtime.capture(b), h.runtime.capture(a)]);
      expect(h.runtime.isCurrent(a), isTrue);
      final captures = h.calls.where((c) => c.method == 'resumeCameraMethod');
      expect(captures, hasLength(1));
      expect(captures.single.arguments, containsPair('captureId', h.leases[1]));
    });

    test('late lease allocation is closed without resuming an obsolete owner', () async {
      final a = h.controller(1);
      final b = h.controller(2);
      final allocation = Completer<String>();
      h.handler = (call) async {
        if (call.method == 'openCapture' && (call.arguments as Map)['viewId'] == 1) {
          return allocation.future;
        }
        return null;
      };
      final old = h.runtime.capture(a);
      await RuntimeHarness.flush();
      await h.runtime.capture(b);
      await old;
      allocation.complete('late-a');
      await RuntimeHarness.flush();
      expect(h.runtime.isCurrent(b), isTrue);
      expect(h.calls.where((c) => c.method == 'resumeCameraMethod'), hasLength(1));
      expect(h.calls.where((c) => c.method == 'closeCapture').last.arguments, {'captureId': 'late-a'});
    });

    test('A B A rejects the original A subscription and accepts the new one', () async {
      final results = <String?>[];
      final a = h.controller(1, scanning: true, onScan: (barcode) => results.add(barcode.rawValue));
      final b = h.controller(2, scanning: true);
      await h.runtime.capture(a);
      final oldLease = h.leases[1]!;
      final oldScan = h.scans[oldLease]!;
      await h.runtime.capture(b);
      await h.runtime.capture(a);
      await h.event(1, 'obsolete', captureId: oldLease, subscriptionId: oldScan);
      await h.event(1, 'current');
      expect(results, ['current']);
    });

    test('scan cancellation revokes delivery immediately while another control waits', () async {
      final results = <String?>[];
      final a = h.controller(1, scanning: true, onScan: (barcode) => results.add(barcode.rawValue));
      await h.runtime.capture(a);
      final oldScan = h.scans[h.leases[1]]!;
      final zoom = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'updateCameraSettings') await zoom.future;
        return null;
      };
      await a.configure(zoomRatio: 2);
      await a.configure(scanEnabled: false);
      await h.event(1, 'stale');
      expect(results, isEmpty);
      await a.configure(scanEnabled: true, scanDelay: 0);
      zoom.complete();
      await RuntimeHarness.flush();
      await h.event(1, 'still stale', subscriptionId: oldScan);
      await h.event(1, 'fresh');
      expect(results, ['fresh']);
    });

    test('terminal disposal cleans all consumers and hardware after unregister fails', () async {
      h.controller(1);
      h.controller(2);
      await RuntimeHarness.flush();
      h.handler = (call) async {
        if (call.method == 'unregisterScanner') {
          throw PlatformException(code: 'unregister');
        }
        return null;
      };
      await expectLater(h.runtime.dispose(), throwsA(isA<PlatformException>()));
      h.terminalFailureExpected = true;
      expect(h.calls.where((c) => c.method == 'unregisterScanner'), hasLength(2));
      expect(h.methods, contains('disposeScanner'));
      // The same terminal operation retains its failure; do not ask the fixture to await it twice.
      h.handler = null;
    });
  });

  group('pause and resume', () {
    TestWidgetsFlutterBinding.ensureInitialized();
    late RuntimeHarness h;
    setUp(() => h = RuntimeHarness());
    tearDown(() => h.dispose());

    test('successive handoffs wait for the original paused frame copy', () async {
      final frame = Completer<void>();
      final a = BarcodeScannerController(viewId: 1, retainPreview: () => frame.future);
      h.controllers.add(a);
      h.runtime.register(a);
      final b = h.controller(2);
      final c = h.controller(3);
      await h.runtime.capture(a);
      await a.configure(cameraPaused: true);
      h.calls.clear();

      final captureB = h.runtime.capture(b);
      final captureC = h.runtime.capture(c);
      await RuntimeHarness.flush();
      expect(h.methods, isNot(contains('resumeCameraMethod')));
      expect(h.methods, isNot(contains('pauseCameraMethod')));
      frame.complete();
      await Future.wait([captureB, captureC]);
      final activations = h.calls.where((call) => call.method == 'resumeCameraMethod');
      expect(activations, hasLength(1));
      expect((activations.single.arguments as Map)['captureId'], h.leases[3]);
    });

    test('manual pause retains capture and resumes with a fresh scan endpoint', () async {
      final results = <Barcode>[];
      final a = h.controller(1, scanning: true, onScan: results.add);
      await h.runtime.capture(a);
      await a.configure(zoomRatio: 3);
      await RuntimeHarness.flush();
      final lease = h.leases[1];
      final oldSubscription = h.scans[lease];
      h.calls.clear();
      await a.configure(cameraPaused: true);
      await RuntimeHarness.flush();
      expect(h.runtime.isCurrent(a), isTrue);
      expect(h.methods, contains('cancelScan'));
      expect(h.methods, isNot(contains('pauseCameraMethod')));
      expect(h.methods, isNot(contains('closeCapture')));
      expect(h.methods, isNot(contains('disposeScanner')));
      await h.event(1, 'paused', subscriptionId: oldSubscription);
      expect(results, isEmpty);
      await h.resumeVisible(a);
      await RuntimeHarness.flush();
      expect(h.runtime.isCurrent(a), isTrue);
      expect(h.leases[1], lease);
      expect(h.scans[lease], isNot(oldSubscription));
      expect(h.methods, isNot(contains('openCapture')));
      expect(h.methods, isNot(contains('resumeCameraMethod')));
      expect(h.methods, isNot(contains('updateCameraSettings')));
      expect(a.configuration.zoomRatio, 3);
      await h.event(1, 'stale', subscriptionId: oldSubscription);
      await h.event(1, 'resumed');
      expect(results.map((barcode) => barcode.rawValue), ['resumed']);
    });

    test('inactive pause never stops the selected consumer', () async {
      final a = h.controller(1);
      final b = h.controller(2);
      await h.runtime.capture(b);
      h.calls.clear();
      await a.configure(cameraPaused: true);
      await a.configure(cameraPaused: false);
      await RuntimeHarness.flush();
      expect(h.calls, isEmpty);
      expect(h.runtime.isCurrent(b), isTrue);
    });

    test('new capture waits for the actual unfinished physical pause', () async {
      final a = h.controller(1);
      final b = h.controller(2);
      await h.runtime.capture(a);
      final pause = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'pauseCameraMethod') await pause.future;
        return null;
      };
      final release = h.runtime.suspend(a);
      h.calls.clear();
      final capture = h.runtime.capture(b);
      await RuntimeHarness.flush();
      expect(h.methods, isNot(contains('openCapture')));
      pause.complete();
      await Future.wait([release, capture]);
      expect(h.runtime.isCurrent(b), isTrue);
    });

    for (final fails in [false, true]) {
      test('release cancels startup and ignores its late reply (failure=$fails)', () async {
        final start = Completer<void>();
        h.handler = (call) async {
          if (call.method == 'resumeCameraMethod') await start.future;
          return null;
        };
        final a = h.controller(1);
        final capture = h.runtime.capture(a);
        await RuntimeHarness.flush();
        await h.runtime.release(a);
        await capture;
        await RuntimeHarness.flush();
        if (fails) {
          start.completeError(PlatformException(code: 'obsolete'));
        } else {
          start.complete();
        }
        await RuntimeHarness.flush();
        expect(h.runtime.isCurrent(a), isFalse);
        expect(h.errors, isEmpty);
      });
    }

    test('manual pause applies controls to the retained capture before resume', () async {
      final a = h.controller(1, scanning: true);
      await h.runtime.capture(a);
      await a.configure(cameraPaused: true);
      await RuntimeHarness.flush();
      h.calls.clear();
      await a.configure(zoomRatio: 3, torchEnabled: true, cropRect: const CropRect(scaleWidth: .5), scanDelay: 200);
      await RuntimeHarness.flush();
      expect(h.methods, ['updateCameraSettings']);
      expect(h.calls.single.arguments, containsPair('torchEnabled', true));
      expect(h.calls.single.arguments, containsPair('cropRect', const CropRect(scaleWidth: .5).toJson()));
      expect(h.methods, isNot(contains('startScan')));
      expect(h.methods, isNot(contains('resumeCameraMethod')));
      for (final call in h.calls) {
        expect(call.arguments, containsPair('captureId', h.leases[1]));
      }
      expect(h.calls.singleWhere((call) => call.method == 'updateCameraSettings').arguments, containsPair('zoomRatio', 3.0));
      h.calls.clear();
      await h.resumeVisible(a);
      await RuntimeHarness.flush();
      expect(h.methods, ['subscribeScan', 'startScan']);
      expect(h.calls.singleWhere((call) => call.method == 'startScan').arguments, containsPair('delay', 200));
    });

    for (final scanning in [false, true]) {
      test('paused pending control retains the latest scanning intent ($scanning)', () async {
        final results = <Barcode>[];
        final a = h.controller(1, scanning: true, onScan: results.add);
        await h.runtime.capture(a);
        final subscription = h.scans[h.leases[1]];
        final zoom = Completer<void>();
        addTearDown(() {
          if (!zoom.isCompleted) zoom.complete();
        });
        h.handler = (call) async {
          if (call.method == 'updateCameraSettings') await zoom.future;
          return null;
        };
        await a.configure(cameraPaused: true);
        await RuntimeHarness.flush();
        h.calls.clear();
        await a.configure(zoomRatio: 3);
        await RuntimeHarness.flush();
        expect(h.methods, ['updateCameraSettings']);
        await a.configure(scanEnabled: !scanning);
        await a.configure(scanEnabled: scanning, scanDelay: 250, torchEnabled: true);
        await h.event(1, 'paused', subscriptionId: subscription);
        expect(results, isEmpty);
        zoom.complete();
        await RuntimeHarness.flush();
        expect(h.methods, ['updateCameraSettings', 'updateCameraSettings']);
        h.calls.clear();
        await h.resumeVisible(a);
        await RuntimeHarness.flush();
        expect(h.methods, scanning ? ['subscribeScan', 'startScan'] : isEmpty);
        if (scanning) {
          expect(h.calls.last.arguments, containsPair('delay', 250));
          await h.event(1, 'old', subscriptionId: subscription);
          expect(results, isEmpty);
          await h.event(1, 'new');
          expect(results.single.rawValue, 'new');
        }
      });
    }

    for (final resumeBeforeCopy in [false, true]) {
      test('paused controls await the saved frame and reread intent (resume=$resumeBeforeCopy)', () async {
        final frame = Completer<void>();
        addTearDown(() {
          if (!frame.isCompleted) frame.complete();
        });
        final a = BarcodeScannerController(viewId: 1, retainPreview: () => frame.future);
        h.controllers.add(a);
        h.runtime.register(a);
        await h.runtime.capture(a);
        h.calls.clear();

        await a.configure(cameraPaused: true, zoomRatio: 2, torchEnabled: true);
        await RuntimeHarness.flush();
        expect(h.calls, isEmpty);
        await a.configure(cameraPaused: !resumeBeforeCopy, zoomRatio: 3, torchEnabled: false);
        frame.complete();
        await RuntimeHarness.flush();
        expect(h.methods, ['updateCameraSettings']);
        expect(h.calls.single.arguments, containsPair('zoomRatio', 3.0));
      });
    }

    test('resume during pending pause cancellation restarts recognition', () async {
      final a = h.controller(1, scanning: true);
      await h.runtime.capture(a);
      final lease = h.leases[1];
      final oldSubscription = h.scans[lease];
      final cancellation = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'cancelScan') await cancellation.future;
        return null;
      };
      h.calls.clear();
      await a.configure(cameraPaused: true);
      await RuntimeHarness.flush();
      expect(h.methods, contains('cancelScan'));
      await h.resumeVisible(a);
      cancellation.complete();
      await RuntimeHarness.flush();
      expect(h.runtime.isCurrent(a), isTrue);
      expect(h.leases[1], lease);
      expect(h.methods, contains('startScan'));
      expect(h.scans[lease], isNot(oldSubscription));
      expect(h.methods, isNot(contains('resumeCameraMethod')));
    });

    test('manual pause during startup lets activation finish without recognition', () async {
      final start = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'resumeCameraMethod') await start.future;
        return null;
      };
      final a = h.controller(1, scanning: true);
      final capture = h.runtime.capture(a);
      await RuntimeHarness.flush();
      final lease = h.leases[1];
      await a.configure(cameraPaused: true);
      start.complete();
      await capture;
      await RuntimeHarness.flush();
      expect(h.runtime.isCurrent(a), isTrue);
      expect(a.configuration.cameraPaused, isTrue);
      expect(h.methods, isNot(contains('pauseCameraMethod')));
      expect(h.methods, isNot(contains('closeCapture')));
      expect(h.methods, isNot(contains('startScan')));
      h.calls.clear();
      await h.resumeVisible(a);
      await RuntimeHarness.flush();
      expect(h.leases[1], lease);
      expect(h.methods, contains('startScan'));
      expect(h.methods, isNot(contains('resumeCameraMethod')));
    });

    test('paused owner hands off capture without physically pausing the camera', () async {
      final valuesA = <Barcode>[];
      final valuesB = <Barcode>[];
      final a = h.controller(1, scanning: true, onScan: valuesA.add);
      final b = h.controller(2, scanning: true, onScan: valuesB.add);
      await h.runtime.capture(a);
      final leaseA = h.leases[1];
      final scanA = h.scans[leaseA];
      h.calls.clear();
      await a.configure(cameraPaused: true);
      await RuntimeHarness.flush();
      await h.runtime.capture(b);
      expect(h.runtime.isCurrent(a), isFalse);
      expect(h.runtime.isCurrent(b), isTrue);
      expect(h.methods, isNot(contains('pauseCameraMethod')));
      expect(h.calls.singleWhere((call) => call.method == 'closeCapture').arguments, containsPair('captureId', leaseA));
      await h.event(1, 'stale A', captureId: leaseA, subscriptionId: scanA);
      await h.event(2, 'active B');
      expect(valuesA, isEmpty);
      expect(valuesB.single.rawValue, 'active B');
    });

    test('failure to stop is reported and does not prevent a later explicit retry', () async {
      final a = h.controller(1);
      await h.runtime.capture(a);
      h.handler = (call) async {
        if (call.method == 'pauseCameraMethod') {
          throw PlatformException(code: 'pause');
        }
        return null;
      };
      await expectLater(h.runtime.suspend(a), throwsA(isA<PlatformException>()));
      expect(h.runtime.isCurrent(a), isFalse);
      h.handler = null;
      await h.runtime.capture(a);
      expect(h.runtime.isCurrent(a), isTrue);
    });
  });

  group('resource lifetime', () {
    TestWidgetsFlutterBinding.ensureInitialized();
    const channel = MethodChannel('mlkit_channel');
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    late ScannerRuntime runtime;
    late List<MethodCall> calls;
    int leaseCount = 0;
    Future<Object?> Function(MethodCall)? handler;

    setUp(() {
      calls = [];
      handler = null;
      runtime = ScannerRuntime(MlKitChannel());
      ScannerRuntime.instance = runtime;
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        if (handler != null) return handler!(call);
        switch (call.method) {
          case 'openCapture':
            return 'lease-${++leaseCount}';
          case 'subscribePreview':
            return {'subscriptionId': 'preview', 'description': null};
          case 'subscribeScan':
            return 'scan-$leaseCount';
          default:
            return null;
        }
      });
    });
    tearDown(() async {
      messenger.setMockMethodCallHandler(channel, null);
    });

    testWidgets('released capture expires even with registered hidden widgets', (tester) async {
      final controller = BarcodeScannerController(viewId: 1);
      runtime.register(controller);
      final capture = runtime.capture(controller);
      await tester.pump();
      await capture;
      await runtime.release(controller);
      expect(calls.where((c) => c.method == 'pauseCameraMethod'), isEmpty);
      await tester.pump(const Duration(milliseconds: 299));
      expect(calls.where((c) => c.method == 'disposeScanner'), isEmpty);
      await tester.pump(const Duration(milliseconds: 1));
      await tester.pump();
      expect(calls.where((c) => c.method == 'disposeScanner'), hasLength(1));
      final resumed = runtime.capture(controller);
      await tester.pump();
      await resumed;
      expect(calls.where((c) => c.method == 'subscribePreview'), hasLength(2));
      expect(runtime.isCurrent(controller), isTrue);
      controller.dispose();
      await runtime.dispose();
    });

    testWidgets('manual pause expires on its original deadline despite control updates', (tester) async {
      final original = MLKitUtils.cameraShutdownDelay;
      addTearDown(() => MLKitUtils.cameraShutdownDelay = original);
      MLKitUtils.cameraShutdownDelay = const Duration(milliseconds: 500);
      final controller = BarcodeScannerController(viewId: 1);
      runtime.register(controller);
      final capture = runtime.capture(controller);
      await tester.pump();
      await capture;
      await controller.configure(cameraPaused: true);
      await tester.pump(const Duration(milliseconds: 400));
      await controller.configure(zoomRatio: 3);
      await tester.pump(const Duration(milliseconds: 99));
      expect(calls.where((c) => c.method == 'disposeScanner'), isEmpty);
      await tester.pump(const Duration(milliseconds: 1));
      await tester.pump();
      expect(calls.where((c) => c.method == 'disposeScanner'), hasLength(1));
      expect(runtime.isCurrent(controller), isFalse);
      await controller.configure(zoomRatio: 4);
      await tester.pump(const Duration(seconds: 1));
      expect(calls.where((c) => c.method == 'openCapture'), hasLength(1));
      controller.dispose();
      await runtime.dispose();
    });

    testWidgets('resuming before pause expiry keeps the same capture', (tester) async {
      final controller = BarcodeScannerController(viewId: 1);
      runtime.register(controller);
      final capture = runtime.capture(controller);
      await tester.pump();
      await capture;
      await controller.configure(cameraPaused: true);
      await tester.pump(const Duration(milliseconds: 299));
      await controller.configure(cameraPaused: false);
      await tester.pump(const Duration(seconds: 1));
      expect(calls.where((c) => c.method == 'disposeScanner'), isEmpty);
      expect(calls.where((c) => c.method == 'openCapture'), hasLength(1));
      await controller.configure(cameraPaused: true);
      await tester.pump(const Duration(milliseconds: 300));
      await tester.pump();
      expect(calls.where((c) => c.method == 'disposeScanner'), hasLength(1));
      controller.dispose();
      await runtime.dispose();
    });

    testWidgets('a new unpaused owner cancels the previous owners pause timeout', (tester) async {
      final a = BarcodeScannerController(viewId: 1);
      final b = BarcodeScannerController(viewId: 2);
      runtime.register(a);
      runtime.register(b);
      final first = runtime.capture(a);
      await tester.pump();
      await first;
      await a.configure(cameraPaused: true);
      await tester.pump(const Duration(milliseconds: 299));
      final second = runtime.capture(b);
      await tester.pump();
      await second;
      await tester.pump(const Duration(seconds: 1));
      expect(calls.where((c) => c.method == 'disposeScanner'), isEmpty);
      expect(runtime.isCurrent(b), isTrue);
      a.dispose();
      b.dispose();
      await runtime.dispose();
    });

    testWidgets('manual pause expires even while initial activation is pending', (tester) async {
      final activation = Completer<void>();
      handler = (call) async {
        switch (call.method) {
          case 'subscribePreview':
            return {'subscriptionId': 'preview', 'description': null};
          case 'openCapture':
            return 'pending-lease';
          case 'resumeCameraMethod':
            await activation.future;
        }
        return null;
      };
      final controller = BarcodeScannerController(viewId: 1);
      runtime.register(controller);
      final capture = runtime.capture(controller);
      await tester.pump();
      await controller.configure(cameraPaused: true);
      await tester.pump(const Duration(milliseconds: 300));
      await tester.pump();
      await capture;
      expect(calls.where((c) => c.method == 'disposeScanner'), hasLength(1));
      expect(runtime.isCurrent(controller), isFalse);
      activation.complete();
      await tester.pump();
      expect(controller.captureState.value, ScannerCaptureState.released);
      expect(calls.where((c) => c.method == 'startScan'), isEmpty);
      controller.dispose();
      await runtime.dispose();
    });

    testWidgets('resuming then pausing during disposal does not restart a cold camera', (tester) async {
      final controller = BarcodeScannerController(viewId: 1);
      runtime.register(controller);
      final first = runtime.capture(controller);
      await tester.pump();
      await first;
      final disposal = Completer<void>();
      handler = (call) async {
        if (call.method == 'disposeScanner') await disposal.future;
        if (call.method == 'subscribePreview') return {'subscriptionId': 'next-preview', 'description': null};
        if (call.method == 'openCapture') return 'next-lease';
        return null;
      };
      await controller.configure(cameraPaused: true);
      await tester.pump(const Duration(milliseconds: 300));
      await tester.pump();
      await controller.configure(cameraPaused: false);
      final resumed = runtime.capture(controller);
      await tester.pump();
      await controller.configure(cameraPaused: true);
      disposal.complete();
      await tester.pump();
      await resumed;
      await tester.pump(const Duration(seconds: 1));
      expect(calls.where((c) => c.method == 'openCapture'), hasLength(1));
      expect(controller.captureState.value, ScannerCaptureState.released);
      await controller.configure(cameraPaused: false);
      final next = runtime.capture(controller);
      await tester.pump();
      await next;
      expect(calls.where((c) => c.method == 'openCapture'), hasLength(2));
      expect(runtime.isCurrent(controller), isTrue);
      controller.dispose();
      await runtime.dispose();
    });

    testWidgets('removing the active widget starts the disposal timer', (tester) async {
      final controller = BarcodeScannerController(viewId: 1);
      final consumer = runtime.register(controller);
      await tester.pump();
      await consumer.ready;
      final capture = runtime.capture(controller);
      await tester.pump();
      await capture;
      await consumer.close();
      await tester.pump(const Duration(milliseconds: 299));
      expect(calls.where((c) => c.method == 'disposeScanner'), isEmpty);
      await tester.pump(const Duration(milliseconds: 1));
      await tester.pump();
      expect(calls.where((c) => c.method == 'disposeScanner'), hasLength(1), reason: calls.map((c) => c.method).join(', '));
      controller.dispose();
      await runtime.dispose();
    });

    testWidgets('shutdown uses the configured delay when the timer is scheduled', (tester) async {
      final original = MLKitUtils.cameraShutdownDelay;
      addTearDown(() => MLKitUtils.cameraShutdownDelay = original);
      MLKitUtils.cameraShutdownDelay = const Duration(seconds: 1);
      final controller = BarcodeScannerController(viewId: 1);
      final consumer = runtime.register(controller);
      await tester.pump();
      await consumer.ready;
      final capture = runtime.capture(controller);
      await tester.pump();
      await capture;
      await consumer.close();

      MLKitUtils.cameraShutdownDelay = Duration.zero;
      await tester.pump(const Duration(milliseconds: 999));
      expect(calls.where((c) => c.method == 'disposeScanner'), isEmpty);
      await tester.pump(const Duration(milliseconds: 1));
      await tester.pump();
      expect(calls.where((c) => c.method == 'disposeScanner'), hasLength(1));
      controller.dispose();
      await runtime.dispose();
    });

    testWidgets('zero shutdown delay disposes resources without a grace period', (tester) async {
      final original = MLKitUtils.cameraShutdownDelay;
      addTearDown(() => MLKitUtils.cameraShutdownDelay = original);
      MLKitUtils.cameraShutdownDelay = Duration.zero;
      final controller = BarcodeScannerController(viewId: 1);
      final consumer = runtime.register(controller);
      await tester.pump();
      await consumer.ready;
      final capture = runtime.capture(controller);
      await tester.pump();
      await capture;
      await consumer.close();

      await tester.pump(Duration.zero);
      expect(calls.where((c) => c.method == 'disposeScanner'), hasLength(1));
      controller.dispose();
      await runtime.dispose();
    });

    testWidgets('registration without capture does not extend the grace period', (tester) async {
      final a = BarcodeScannerController(viewId: 1);
      final b = BarcodeScannerController(viewId: 2);
      final first = runtime.register(a);
      final capture = runtime.capture(a);
      await tester.pump();
      await capture;
      await first.close();
      await tester.pump(const Duration(milliseconds: 299));
      runtime.register(b);
      await tester.pump(const Duration(seconds: 1));
      expect(calls.where((c) => c.method == 'disposeScanner'), hasLength(1));
      a.dispose();
      b.dispose();
      await runtime.dispose();
    });

    testWidgets('new active capture cancels expiry and its release starts a fresh deadline', (tester) async {
      final a = BarcodeScannerController(viewId: 1);
      final b = BarcodeScannerController(viewId: 2);
      runtime.register(a);
      runtime.register(b);
      final first = runtime.capture(a);
      await tester.pump();
      await first;
      await runtime.release(a);
      await tester.pump(const Duration(milliseconds: 299));
      final second = runtime.capture(b);
      await tester.pump();
      await second;
      await tester.pump(const Duration(seconds: 1));
      expect(calls.where((c) => c.method == 'disposeScanner'), isEmpty);
      expect(calls.where((c) => c.method == 'subscribePreview'), hasLength(1));
      await runtime.release(b);
      await tester.pump(const Duration(milliseconds: 299));
      expect(calls.where((c) => c.method == 'disposeScanner'), isEmpty);
      await tester.pump(const Duration(milliseconds: 1));
      await tester.pump();
      expect(calls.where((c) => c.method == 'disposeScanner'), hasLength(1));
      a.dispose();
      b.dispose();
      await runtime.dispose();
    });

    testWidgets('capture during snapshot disposal waits and cannot revive the old preview', (tester) async {
      final retention = Completer<void>();
      final a = BarcodeScannerController(viewId: 1, retainPreview: () => retention.future);
      final b = BarcodeScannerController(viewId: 2);
      runtime.register(a);
      runtime.register(b);
      final first = runtime.capture(a);
      await tester.pump();
      await first;
      await runtime.release(a);
      await tester.pump(const Duration(milliseconds: 300));
      final second = runtime.capture(b);
      await tester.pump();
      await messenger.handlePlatformMessage(
        'mlkit_channel',
        const StandardMethodCodec().encodeMethodCall(
          const MethodCall('onPreviewState', {
            'subscriptionId': 'preview',
            'description': {'textureId': 42, 'width': 100, 'height': 100, 'rotationDegrees': 0, 'mirrored': false, 'state': 'streaming'},
          }),
        ),
        (_) {},
      );
      await tester.pump();
      expect(runtime.preview.value, isNull);
      expect(calls.where((c) => c.method == 'disposeScanner'), isEmpty);
      expect(calls.where((c) => c.method == 'resumeCameraMethod'), hasLength(1));
      retention.complete();
      await tester.pump();
      await second;
      expect(calls.where((c) => c.method == 'disposeScanner'), hasLength(1));
      expect(calls.where((c) => c.method == 'subscribePreview'), hasLength(2));
      expect(runtime.isCurrent(b), isTrue);
      a.dispose();
      b.dispose();
      await runtime.dispose();
    });

    testWidgets('capture handoff does not pause hardware', (tester) async {
      final a = BarcodeScannerController(viewId: 1);
      final b = BarcodeScannerController(viewId: 2);
      runtime.register(a);
      runtime.register(b);
      runtime.updateGeometry(a, const Size(400, 400));
      runtime.updateGeometry(b, const Size(400, 800));
      final first = runtime.capture(a);
      await tester.pump();
      await first;
      final second = runtime.capture(b);
      await tester.pump();
      await second;
      expect(calls.where((c) => c.method == 'pauseCameraMethod'), isEmpty);
      expect(runtime.isCurrent(b), isTrue);
      a.dispose();
      b.dispose();
      await runtime.dispose();
    });

    testWidgets('registration during disposal waits for the actual operation', (tester) async {
      final a = BarcodeScannerController(viewId: 1);
      final first = runtime.register(a);
      final capture = runtime.capture(a);
      await tester.pump();
      await capture;
      await first.close();
      final disposal = Completer<void>();
      handler = (call) async {
        if (call.method == 'disposeScanner') await disposal.future;
        if (call.method == 'subscribePreview') {
          return {'subscriptionId': 'next-preview', 'description': null};
        }
        return null;
      };
      await tester.pump(const Duration(milliseconds: 300));
      calls.clear();
      final b = BarcodeScannerController(viewId: 2);
      runtime.register(b);
      await tester.pump();
      expect(calls, isEmpty);
      disposal.complete();
      await tester.pump();
      expect(calls.where((c) => c.method == 'registerScanner'), hasLength(1));
      a.dispose();
      b.dispose();
      await runtime.dispose();
    });

    testWidgets('registration from preview withdrawal waits for the published disposal barrier', (tester) async {
      final a = BarcodeScannerController(viewId: 1);
      final b = BarcodeScannerController(viewId: 2);
      final first = runtime.register(a);
      final capture = runtime.capture(a);
      await tester.pump();
      await capture;
      runtime.preview.value = const ScannerPreviewDescription(textureId: 42, size: Size(100, 100), status: ScannerPreviewStatus.streaming);
      await first.close();
      final disposal = Completer<void>();
      handler = (call) async {
        if (call.method == 'disposeScanner') await disposal.future;
        if (call.method == 'subscribePreview') return {'subscriptionId': 'next-preview', 'description': null};
        if (call.method == 'openCapture') return 'next-lease';
        return null;
      };
      void registerReplacement() {
        if (runtime.preview.value == null) {
          runtime.register(b);
          unawaited(runtime.capture(b));
        }
      }

      runtime.preview.addListener(registerReplacement);
      calls.clear();
      await tester.pump(const Duration(milliseconds: 300));
      expect(calls.where((call) => call.method == 'disposeScanner'), hasLength(1));
      expect(calls.where((call) => call.method == 'subscribePreview' || call.method == 'registerScanner'), isEmpty);
      disposal.complete();
      await tester.pump();
      expect(calls.where((call) => call.method == 'subscribePreview'), hasLength(1));
      expect(calls.where((call) => call.method == 'registerScanner'), hasLength(1));
      runtime.preview.removeListener(registerReplacement);
      a.dispose();
      b.dispose();
      await runtime.dispose();
    });
  });

  group('preview handoff and subscriptions', () {
    late RuntimeHarness h;
    setUp(() => h = RuntimeHarness());
    tearDown(() => h.dispose());
    Map<String, Object> description(int id) => {
      'textureId': id,
      'width': 1280,
      'height': 720,
      'rotationDegrees': 90,
      'mirrored': false,
      'state': 'streaming',
    };

    test('shared preview remains available while the next owner is starting', () async {
      final a = h.controller(1);
      final b = h.controller(2);
      await h.runtime.capture(a);
      await h.send(MethodCall('onPreviewState', {'subscriptionId': 'preview', 'description': description(42)}));
      final pending = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'resumeCameraMethod') await pending.future;
        return null;
      };
      final capture = h.runtime.capture(b);
      await RuntimeHarness.flush();
      expect(h.runtime.preview.value?.textureId, 42);
      pending.complete();
      await capture;
      expect(h.runtime.preview.value?.textureId, 42);
    });

    test('events from an obsolete preview subscription cannot replace current output', () async {
      final controller = h.controller(1);
      unawaited(h.runtime.capture(controller));
      await RuntimeHarness.flush();
      await h.send(MethodCall('onPreviewState', {'subscriptionId': 'preview', 'description': description(42)}));
      await h.send(const MethodCall('onPreviewState', {'subscriptionId': 'obsolete', 'description': null}));
      expect(h.runtime.preview.value?.textureId, 42);
    });

    test('preview event arriving before subscribe reply wins over initial snapshot', () async {
      final reply = Completer<Object?>();
      h.handler = (call) async {
        if (call.method == 'subscribePreview') return reply.future;
        return null;
      };
      final controller = h.controller(1);
      unawaited(h.runtime.capture(controller));
      await RuntimeHarness.flush();
      await h.send(MethodCall('onPreviewState', {'subscriptionId': 'early', 'description': description(7)}));
      reply.complete({'subscriptionId': 'early', 'description': null});
      await RuntimeHarness.flush();
      expect(h.runtime.preview.value?.textureId, 7);
    });
  });
}
