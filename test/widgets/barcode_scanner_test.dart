import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';
import 'package:mlkit_scanner/widgets/scanner_overlay.dart';
import '../support/runtime_harness.dart';

void main() {
  final blackPlaceholder = find.byWidgetPredicate((widget) => widget is ColoredBox && widget.color == Colors.black);
  group('lifecycle', () {
    TestWidgetsFlutterBinding.ensureInitialized();
    late RuntimeHarness h;
    setUp(() => h = RuntimeHarness());
    tearDown(() => h.dispose());
    Widget app(Widget child) => MaterialApp(home: Scaffold(body: child));
    Widget scanner({ValueChanged<Object>? error}) => BarcodeScanner(onScan: (_) {}, onError: error);

    testWidgets('widget registers once while camera startup is pending', (tester) async {
      final start = Completer<void>();
      h.handler = (call) async {
        if (call.method == 'resumeCameraMethod') await start.future;
        return null;
      };
      await tester.pumpWidget(app(scanner()));
      await tester.pump();
      expect(h.methods, contains('resumeCameraMethod'));
      expect(blackPlaceholder, findsOneWidget);
      expect(h.methods.where((m) => m == 'registerScanner'), hasLength(1));
      start.complete();
      await tester.pump();
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(milliseconds: 300));
      await h.dispose();
    });

    testWidgets('warm next route immediately renders the existing texture', (tester) async {
      final navigator = GlobalKey<NavigatorState>();
      await tester.pumpWidget(MaterialApp(navigatorKey: navigator, home: Scaffold(body: scanner())));
      await tester.pump();
      await h.send(
        const MethodCall('onPreviewState', {
          'subscriptionId': 'preview',
          'description': {'textureId': 77, 'width': 1280, 'height': 720, 'rotationDegrees': 90, 'mirrored': false, 'state': 'streaming'},
        }),
      );
      await tester.pump();
      h.calls.clear();
      unawaited(navigator.currentState!.push(MaterialPageRoute<void>(builder: (_) => Scaffold(body: scanner()))));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 350));
      expect(blackPlaceholder, findsNothing);
      expect(tester.widgetList<Texture>(find.byType(Texture)).map((texture) => texture.textureId), everyElement(77));
      expect(h.methods, isNot(contains('pauseCameraMethod')));
      navigator.currentState!.pop();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 350));
      expect(tester.widgetList<Texture>(find.byType(Texture)).map((texture) => texture.textureId), everyElement(77));
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(milliseconds: 300));
      await h.dispose();
    });

    testWidgets('hidden registered widget prevents the zero-consumer disposal timer', (tester) async {
      final key = GlobalKey();
      Widget page(bool enabled) => app(TickerMode(enabled: enabled, child: BarcodeScanner(key: key, onScan: (_) {})));
      await tester.pumpWidget(page(true));
      await tester.pump();
      await tester.pumpWidget(page(false));
      await tester.pump(const Duration(seconds: 1));
      expect(h.methods, contains('pauseCameraMethod'));
      expect(h.methods, isNot(contains('disposeScanner')));
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(milliseconds: 300));
      await h.dispose();
    });

    testWidgets('initial settings and laid-out geometry reach capture', (tester) async {
      await tester.pumpWidget(
        app(
          BarcodeScanner(
            onScan: (_) {},
            scanning: true,
            scanDelay: 200,
            zoomRatio: 2,
            flashEnabled: true,
            cropRect: const CropRect(scaleWidth: .6),
          ),
        ),
      );
      await tester.pump();
      final resume = h.calls.singleWhere((c) => c.method == 'resumeCameraMethod');
      expect((resume.arguments as Map)['configuration'], allOf(containsPair('zoomRatio', 2.0), containsPair('torchEnabled', true)));
      expect(h.methods, contains('startScan'));
      expect(find.byType(ScannerOverlay), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(milliseconds: 300));
      await h.dispose();
    });

    testWidgets('capture errors reach the widget error callback', (tester) async {
      final errors = <Object>[];
      h.handler = (call) async {
        if (call.method == 'resumeCameraMethod') {
          throw PlatformException(code: 'camera');
        }
        return null;
      };
      await tester.pumpWidget(app(scanner(error: errors.add)));
      await tester.pump();
      expect(errors.single, isA<PlatformException>());
      expect((errors.single as PlatformException).code, 'camera');
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(milliseconds: 300));
      await h.dispose();
    });
  });

  group('parameters', () {
    late RuntimeHarness harness;
    setUp(() => harness = RuntimeHarness());
    tearDown(() async {
      debugDefaultTargetPlatformOverride = null;
      await harness.dispose();
    });

    Widget page({
      double zoom = 1,
      bool flash = false,
      CropRect? crop,
      IosCamera? camera,
      bool paused = false,
      bool scanning = false,
      int delay = 0,
      ValueChanged<Object>? onError,
      ValueChanged<bool>? onFlash,
      ValueChanged<Barcode>? onScan,
    }) => MaterialApp(
      home: SizedBox(
        width: 300,
        height: 400,
        child: BarcodeScanner(
          zoomRatio: zoom,
          flashEnabled: flash,
          cropRect: crop,
          camera: camera,
          cameraPaused: paused,
          scanning: scanning,
          scanDelay: delay,
          onScan: onScan ?? (_) {},
          onError: onError,
          onChangeFlashState: onFlash,
        ),
      ),
    );

    Future<void> close(WidgetTester tester) async {
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(milliseconds: 300));
      await harness.dispose();
    }

    testWidgets('widget parameters configure capture and recognition without a controller', (tester) async {
      await tester.pumpWidget(page(zoom: 2, flash: true, scanning: true, delay: 200, crop: const CropRect(scaleWidth: .6)));
      await tester.pump();
      final resume = harness.calls.singleWhere((call) => call.method == 'resumeCameraMethod');
      expect((resume.arguments as Map)['configuration'], allOf(containsPair('zoomRatio', 2.0), containsPair('torchEnabled', true)));
      expect(harness.calls.singleWhere((call) => call.method == 'startScan').arguments, containsPair('delay', 200));
      expect(harness.methods.toList().indexOf('subscribeScan'), lessThan(harness.methods.toList().indexOf('startScan')));
      await close(tester);
    });

    testWidgets('didUpdateWidget applies settings without registering or recapturing', (tester) async {
      await tester.pumpWidget(page());
      await tester.pump();
      harness.calls.clear();
      await tester.pumpWidget(page(zoom: 3, flash: true, scanning: true, delay: 150, crop: const CropRect(scaleWidth: .5)));
      await tester.pump();
      expect(harness.methods, ['updateCameraSettings', 'subscribeScan', 'startScan']);
      expect(harness.calls.first.arguments, {
        'captureId': harness.leases.values.single,
        'zoomRatio': 3.0,
        'torchEnabled': true,
        'cropRect': const CropRect(scaleWidth: .5).toJson(),
      });
      expect(harness.methods, isNot(contains('setScanDelay')));
      expect(harness.calls.singleWhere((call) => call.method == 'startScan').arguments, containsPair('delay', 150));
      expect(harness.methods, isNot(contains('registerScanner')));
      expect(harness.methods, isNot(contains('resumeCameraMethod')));
      harness.calls.clear();
      await tester.pumpWidget(page(zoom: 3, flash: true, scanning: true, delay: 150, crop: const CropRect(scaleWidth: .5)));
      await tester.pump();
      expect(harness.calls, isEmpty);
      await close(tester);
    });

    testWidgets('null crop restores the full preview', (tester) async {
      await tester.pumpWidget(page(crop: const CropRect(scaleWidth: .5)));
      await tester.pump();
      harness.calls.clear();
      await tester.pumpWidget(page());
      await tester.pump();
      final crop = harness.calls.singleWhere((call) => call.method == 'updateCameraSettings');
      expect((crop.arguments as Map)['cropRect'], const CropRect().toJson());
      await close(tester);
    });

    testWidgets('initial pause allocates no capture and later resume uses latest settings', (tester) async {
      await tester.pumpWidget(page(paused: true, zoom: 2, scanning: true));
      await tester.pump();
      expect(harness.methods, isNot(contains('openCapture')));
      await tester.pumpWidget(page(paused: false, zoom: 3, scanning: true, delay: 50));
      await tester.pump();
      final resume = harness.calls.singleWhere((call) => call.method == 'resumeCameraMethod');
      expect((resume.arguments as Map)['configuration'], containsPair('zoomRatio', 3.0));
      expect(harness.methods, contains('startScan'));
      await tester.pumpWidget(page(paused: true, zoom: 3, scanning: true, delay: 50));
      await tester.pump();
      expect(harness.methods, contains('cancelScan'));
      expect(harness.methods, isNot(contains('pauseCameraMethod')));
      expect(harness.methods, isNot(contains('closeCapture')));
      expect(harness.methods, isNot(contains('disposeScanner')));
      await close(tester);
    });

    testWidgets('scan cancellation during a blocked zoom revokes results immediately', (tester) async {
      final values = <Barcode>[];
      await tester.pumpWidget(page(scanning: true, onScan: values.add));
      await tester.pump();
      final viewId = harness.leases.keys.single;
      final zoom = Completer<void>();
      harness.handler = (call) async {
        if (call.method == 'updateCameraSettings') await zoom.future;
        return null;
      };
      await tester.pumpWidget(page(zoom: 2, scanning: true, onScan: values.add));
      await tester.pump();
      await tester.pumpWidget(page(zoom: 2, scanning: false, onScan: values.add));
      await harness.event(viewId, 'stale');
      await tester.pump();
      expect(values, isEmpty);
      zoom.complete();
      await tester.pump();
      expect(harness.methods, contains('cancelScan'));
      expect(harness.methods, isNot(contains('pauseCameraMethod')));
      await close(tester);
    });

    testWidgets('changed error callback receives a typed control failure once', (tester) async {
      final first = <Object>[];
      final latest = <Object>[];
      await tester.pumpWidget(page(onError: first.add));
      await tester.pump();
      harness.handler = (call) async {
        if (call.method == 'updateCameraSettings') {
          throw PlatformException(code: '9', details: {'operation': 'zoom'});
        }
        return null;
      };
      await tester.pumpWidget(page(zoom: 2, onError: latest.add));
      await tester.pump();
      expect(first, isEmpty);
      expect(latest, hasLength(1));
      expect(latest.single, isA<CameraControlException>());
      expect(harness.errors, isEmpty);
      await close(tester);
    });

    testWidgets('validation errors allow parent replacement without setState during build', (tester) async {
      final errors = <Object>[];
      var broken = false;
      await tester.pumpWidget(
        MaterialApp(
          home: StatefulBuilder(
            builder: (context, setState) {
              if (broken) return const Text('Scanner failed');
              return BarcodeScanner(
                zoomRatio: 0,
                onScan: (_) {},
                onError: (error) {
                  errors.add(error);
                  setState(() => broken = true);
                },
              );
            },
          ),
        ),
      );
      await tester.pump();
      expect(errors, hasLength(1));
      expect(errors.single, isArgumentError);
      expect(find.text('Scanner failed'), findsOneWidget);
      expect(harness.methods, isNot(contains('openCapture')));
      expect(harness.errors, isEmpty);
      await close(tester);
    });

    for (final method in ['registerScanner', 'resumeCameraMethod', 'focus', 'pauseCameraMethod']) {
      testWidgets('$method failure reaches only this widget onError once', (tester) async {
        final errors = <Object>[];
        harness.handler = (call) async {
          if (call.method == method) throw PlatformException(code: method);
          return null;
        };
        await tester.pumpWidget(page(onError: errors.add));
        await tester.pump();
        if (method == 'focus') {
          await tester.tap(find.byType(ScannerOverlay));
          await tester.pump();
        } else if (method == 'pauseCameraMethod') {
          tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
          await tester.pump();
        }
        expect(errors, hasLength(1));
        expect((errors.single as PlatformException).code, method);
        expect(harness.errors, isEmpty);
        harness.handler = null;
        await close(tester);
        if (method == 'pauseCameraMethod') {
          tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
        }
      });
    }

    testWidgets('disposed scanner ignores a late camera startup error', (tester) async {
      final errors = <Object>[];
      final start = Completer<void>();
      harness.handler = (call) async {
        if (call.method == 'resumeCameraMethod') await start.future;
        return null;
      };
      await tester.pumpWidget(page(onError: errors.add));
      await tester.pump();
      await tester.pumpWidget(const SizedBox());
      start.completeError(PlatformException(code: 'late'));
      await tester.pump();
      expect(errors, isEmpty);
      expect(harness.errors, isEmpty);
      await close(tester);
    });

    testWidgets('pending startup applies only the latest widget settings', (tester) async {
      final start = Completer<void>();
      harness.handler = (call) async {
        if (call.method == 'resumeCameraMethod') await start.future;
        return null;
      };
      await tester.pumpWidget(page());
      await tester.pump();
      await tester.pumpWidget(page(zoom: 2, scanning: true, delay: 100));
      await tester.pumpWidget(page(zoom: 4, scanning: false, delay: 250));
      start.complete();
      await tester.pump();
      final zoom = harness.calls.singleWhere((call) => call.method == 'updateCameraSettings');
      expect((zoom.arguments as Map)['zoomRatio'], 4.0);
      expect(harness.methods, isNot(contains('startScan')));
      expect(harness.methods.where((method) => method == 'openCapture'), hasLength(1));
      await close(tester);
    });

    testWidgets('invalid snapshot applies no partial changes and can be corrected', (tester) async {
      final errors = <Object>[];
      await tester.pumpWidget(page(zoom: 2, onError: errors.add));
      await tester.pump();
      harness.calls.clear();
      await tester.pumpWidget(page(zoom: 3, flash: true, delay: -1, onError: errors.add));
      await tester.pump();
      expect(errors.single, isA<RangeError>());
      expect(harness.calls, isEmpty);
      await tester.pumpWidget(page(zoom: 3, flash: true, delay: 100, onError: errors.add));
      await tester.pump();
      expect(harness.methods, ['updateCameraSettings']);
      expect(harness.methods, isNot(contains('setScanDelay')));
      expect(harness.methods, isNot(contains('openCapture')));
      await close(tester);
    });

    testWidgets('correcting invalid initial settings starts the existing widget', (tester) async {
      final errors = <Object>[];
      await tester.pumpWidget(page(zoom: 0, onError: errors.add));
      await tester.pump();
      expect(errors, hasLength(1));
      expect(harness.methods, isNot(contains('openCapture')));
      await tester.pumpWidget(page(zoom: 2, onError: errors.add));
      await tester.pump();
      expect(harness.methods, contains('resumeCameraMethod'));
      expect(harness.methods.where((method) => method == 'registerScanner'), hasLength(1));
      await close(tester);
    });

    testWidgets('new scan and torch callbacks replace old ones without recapturing', (tester) async {
      final oldScans = <Barcode>[];
      final newScans = <Barcode>[];
      final oldFlash = <bool>[];
      final newFlash = <bool>[];
      await tester.pumpWidget(page(scanning: true, onScan: oldScans.add, onFlash: oldFlash.add));
      await tester.pump();
      harness.calls.clear();
      await tester.pumpWidget(page(scanning: true, onScan: newScans.add, onFlash: newFlash.add));
      await harness.event(harness.leases.keys.single, 'updated callback');
      expect(oldScans, isEmpty);
      expect(oldFlash, isEmpty);
      expect(newScans.single.rawValue, 'updated callback');
      expect(newFlash, [true]);
      expect(harness.calls, isEmpty);
      await close(tester);
    });

    testWidgets('null camera restores the default on the same lease', (tester) async {
      debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
      await tester.pumpWidget(page(camera: const IosCamera(position: IosCameraPosition.front, type: IosCameraType.builtInWideAngleCamera)));
      await tester.pump();
      harness.calls.clear();
      await tester.pumpWidget(page());
      await tester.pump();
      final resume = harness.calls.singleWhere((call) => call.method == 'resumeCameraMethod');
      expect((resume.arguments as Map)['configuration'], isNot(contains('iosCamera')));
      expect(harness.methods, isNot(contains('openCapture')));
      await close(tester);
      debugDefaultTargetPlatformOverride = null;
    });

    testWidgets('unchanged rebuild after startup failure does not retry automatically', (tester) async {
      final errors = <Object>[];
      harness.handler = (call) async {
        if (call.method == 'resumeCameraMethod') {
          throw PlatformException(code: 'camera');
        }
        return null;
      };
      await tester.pumpWidget(page(onError: errors.add));
      await tester.pump();
      expect(errors, hasLength(1));
      harness.calls.clear();
      await tester.pumpWidget(page(onError: errors.add));
      await tester.pump();
      expect(harness.calls, isEmpty);
      expect(errors, hasLength(1));
      await close(tester);
    });

    testWidgets('missing error callback preserves Flutter error reporting', (tester) async {
      await tester.pumpWidget(page());
      await tester.pump();
      harness.handler = (call) async {
        if (call.method == 'updateCameraSettings') throw PlatformException(code: 'torch');
        return null;
      };
      await tester.pumpWidget(page(flash: true));
      await tester.pump();
      expect((tester.takeException() as PlatformException).code, 'torch');
      expect(tester.takeException(), isNull);
      await close(tester);
    });
  });

  group('error delivery', () {
    late RuntimeHarness harness;
    setUp(() => harness = RuntimeHarness());
    tearDown(() => harness.dispose());

    Future<void> close(WidgetTester tester) async {
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(milliseconds: 300));
      await harness.dispose();
    }

    testWidgets('failed pause belongs to A while B waits to capture', (tester) async {
      final errorsA = <Object>[];
      final errorsB = <Object>[];
      final pause = Completer<void>();
      var visibleA = true;
      var visibleB = false;
      late StateSetter update;
      harness.handler = (call) async {
        if (call.method == 'pauseCameraMethod') await pause.future;
        return null;
      };
      await tester.pumpWidget(
        MaterialApp(
          home: StatefulBuilder(
            builder: (context, setState) {
              update = setState;
              return Row(
                children: [
                  Expanded(child: TickerMode(enabled: visibleA, child: BarcodeScanner(onScan: (_) {}, onError: errorsA.add))),
                  Expanded(child: TickerMode(enabled: visibleB, child: BarcodeScanner(onScan: (_) {}, onError: errorsB.add))),
                ],
              );
            },
          ),
        ),
      );
      await tester.pump();
      update(() => visibleA = false);
      await tester.pump();
      expect(harness.methods.where((method) => method == 'pauseCameraMethod'), hasLength(1));
      update(() => visibleB = true);
      await tester.pump();
      expect(harness.methods.where((method) => method == 'openCapture'), hasLength(1));

      pause.completeError(PlatformException(code: 'pause-A'));
      await tester.pump();
      expect(errorsA, hasLength(1));
      expect((errorsA.single as PlatformException).code, 'pause-A');
      expect(errorsB, isEmpty);
      expect(harness.errors, isEmpty);
      expect(harness.methods.where((method) => method == 'resumeCameraMethod'), hasLength(2));
      await close(tester);
    });

    testWidgets('focus and settings sharing a failing drain report once', (tester) async {
      final errors = <Object>[];
      final zoom = Completer<void>();
      Widget page(double value) => MaterialApp(home: BarcodeScanner(zoomRatio: value, onScan: (_) {}, onError: errors.add));
      await tester.pumpWidget(page(1));
      await tester.pump();
      harness.handler = (call) async {
        if (call.method == 'updateCameraSettings') await zoom.future;
        return null;
      };
      await tester.pumpWidget(page(2));
      await tester.pump();
      await tester.tap(find.byType(ScannerOverlay));
      await tester.pump();
      zoom.completeError(PlatformException(code: '9', details: {'operation': 'zoom'}));
      await tester.pump();
      expect(errors, hasLength(1));
      expect(errors.single, isA<CameraControlException>());
      expect(harness.errors, isEmpty);
      await close(tester);
    });

    testWidgets('active initial scan delivers results before start reply', (tester) async {
      final results = <Barcode>[];
      final startScan = Completer<void>();
      harness.handler = (call) async {
        if (call.method == 'startScan') await startScan.future;
        return null;
      };
      await tester.pumpWidget(MaterialApp(home: BarcodeScanner(scanning: true, onScan: results.add)));
      await tester.pump();
      expect(harness.methods, contains('startScan'));
      await harness.event(harness.leases.keys.single, 'first');
      startScan.complete();
      await tester.pump();
      expect(results.map((barcode) => barcode.rawValue), ['first']);
      await close(tester);
    });
  });

  group('modal routes', () {
    late RuntimeHarness harness;
    setUp(() => harness = RuntimeHarness());
    tearDown(() => harness.dispose());

    Future<void> close(WidgetTester tester) async {
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(milliseconds: 300));
      await harness.dispose();
    }

    testWidgets('delay dropdown pauses recognition without replacing the camera session', (tester) async {
      var delay = 0;
      final values = <Barcode>[];
      await tester.pumpWidget(
        MaterialApp(
          home: StatefulBuilder(
            builder:
                (context, setState) => Scaffold(
                  body: BarcodeScanner(onScan: values.add, scanning: true, zoomRatio: 2, flashEnabled: true, scanDelay: delay),
                  floatingActionButton: PopupMenuButton<int>(
                    tooltip: 'Delay',
                    onSelected: (value) => setState(() => delay = value),
                    itemBuilder: (_) => [const PopupMenuItem(value: 200, child: Text('200 ms'))],
                  ),
                ),
          ),
        ),
      );
      await tester.pump();
      final viewId = harness.leases.keys.single;
      final lease = harness.leases[viewId];
      harness.calls.clear();

      await tester.tap(find.byTooltip('Delay'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pump();
      expect(harness.methods, contains('cancelScan'));
      expect(harness.methods, isNot(contains('pauseCameraMethod')));
      expect(harness.methods, isNot(contains('closeCapture')));
      await harness.event(viewId, 'behind dropdown');
      expect(values, isEmpty);

      await tester.tap(find.text('200 ms'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pump();
      expect(harness.calls.singleWhere((call) => call.method == 'startScan').arguments, containsPair('delay', 200));
      expect(harness.methods, isNot(contains('setScanDelay')));
      expect(harness.methods, isNot(contains('resumeCameraMethod')));
      expect(harness.methods, isNot(contains('updateCameraSettings')));
      expect(harness.leases[viewId], lease);
      await harness.event(viewId, 'after dropdown');
      expect(values.single.rawValue, 'after dropdown');
      await close(tester);
    });

    testWidgets('dialog scanner takes ownership and returns it to the page on close', (tester) async {
      final navigator = GlobalKey<NavigatorState>();
      final mainValues = <Barcode>[];
      final modalValues = <Barcode>[];
      late StateSetter rebuild;
      var mainZoom = 2.0;
      await tester.pumpWidget(
        MaterialApp(
          navigatorKey: navigator,
          home: StatefulBuilder(
            builder: (context, setState) {
              rebuild = setState;
              return Scaffold(body: BarcodeScanner(onScan: mainValues.add, scanning: true, zoomRatio: mainZoom));
            },
          ),
        ),
      );
      await tester.pump();
      final mainView = harness.leases.keys.single;
      final oldLease = harness.leases[mainView];
      harness.calls.clear();

      unawaited(
        showDialog<void>(
          context: navigator.currentContext!,
          builder:
              (_) => Dialog(
                child: SizedBox(width: 250, height: 300, child: BarcodeScanner(onScan: modalValues.add, scanning: true, zoomRatio: 3)),
              ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pump();
      final modalView = harness.leases.keys.singleWhere((id) => id != mainView);
      expect(harness.methods, contains('openCapture'));
      expect(harness.methods, isNot(contains('pauseCameraMethod')));
      await harness.event(mainView, 'stale page', captureId: oldLease);
      await harness.event(modalView, 'in dialog');
      expect(mainValues, isEmpty);
      expect(modalValues.single.rawValue, 'in dialog');

      harness.calls.clear();
      rebuild(() => mainZoom = 4);
      await tester.pump();
      expect(harness.methods, isNot(contains('openCapture')));
      expect(harness.methods, isNot(contains('updateCameraSettings')));

      navigator.currentState!.pop();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pump();
      final resume = harness.calls.singleWhere((call) => call.method == 'resumeCameraMethod');
      expect((resume.arguments as Map)['captureId'], harness.leases[mainView]);
      expect((resume.arguments as Map)['configuration'], containsPair('zoomRatio', 4.0));
      expect(harness.methods, isNot(contains('pauseCameraMethod')));
      await harness.event(modalView, 'stale dialog');
      await harness.event(mainView, 'back on page');
      expect(mainValues.single.rawValue, 'back on page');
      expect(modalValues, hasLength(1));
      await close(tester);
    });

    testWidgets('stacked ordinary dialogs keep the session and scanning resumes only after both close', (tester) async {
      final navigator = GlobalKey<NavigatorState>();
      await tester.pumpWidget(MaterialApp(navigatorKey: navigator, home: Scaffold(body: BarcodeScanner(onScan: (_) {}, scanning: true))));
      await tester.pump();
      harness.calls.clear();
      for (var i = 0; i < 2; i++) {
        unawaited(showDialog<void>(context: navigator.currentContext!, builder: (_) => const AlertDialog(content: Text('Modal'))));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 600));
        await tester.pump();
      }
      expect(harness.methods.where((method) => method == 'cancelScan'), hasLength(1));
      navigator.currentState!.pop();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pump();
      expect(harness.methods, isNot(contains('startScan')));
      navigator.currentState!.pop();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pump();
      expect(harness.methods.where((method) => method == 'startScan'), hasLength(1));
      expect(harness.methods, isNot(contains('closeCapture')));
      expect(harness.methods, isNot(contains('resumeCameraMethod')));
      await close(tester);
    });

    testWidgets('opaque page without a scanner still stops camera work', (tester) async {
      final navigator = GlobalKey<NavigatorState>();
      await tester.pumpWidget(MaterialApp(navigatorKey: navigator, home: Scaffold(body: BarcodeScanner(onScan: (_) {}, scanning: true))));
      await tester.pump();
      harness.calls.clear();
      unawaited(navigator.currentState!.push(MaterialPageRoute<void>(builder: (_) => const Scaffold(body: Text('Another page')))));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pump();
      expect(harness.methods, contains('pauseCameraMethod'));
      expect(harness.methods, contains('closeCapture'));
      expect(harness.methods, isNot(contains('disposeScanner')));
      navigator.currentState!.pop();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pump();
      expect(harness.methods, contains('resumeCameraMethod'));
      await close(tester);
    });
  });

  group('background with a modal', () {
    late RuntimeHarness harness;
    setUp(() => harness = RuntimeHarness());
    tearDown(() => harness.dispose());

    Future<void> openPopup(WidgetTester tester, GlobalKey<NavigatorState> navigator) async {
      unawaited(showDialog<void>(context: navigator.currentContext!, builder: (_) => const AlertDialog(content: Text('Popup'))));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pump();
    }

    Future<void> close(WidgetTester tester) async {
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(milliseconds: 300));
      await harness.dispose();
    }

    testWidgets('returning to the app restores preview beneath an ordinary popup', (tester) async {
      final navigator = GlobalKey<NavigatorState>();
      final values = <Barcode>[];
      await tester.pumpWidget(
        MaterialApp(navigatorKey: navigator, home: Scaffold(body: BarcodeScanner(scanning: true, onScan: values.add))),
      );
      await tester.pump();
      final viewId = harness.leases.keys.single;
      await openPopup(tester, navigator);
      expect(harness.methods, contains('cancelScan'));

      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      await tester.pump();
      expect(harness.methods, contains('pauseCameraMethod'));
      harness.calls.clear();
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pump();

      expect(find.text('Popup'), findsOneWidget);
      final resumes = harness.calls.where((call) => call.method == 'resumeCameraMethod');
      expect(resumes, hasLength(1));
      expect((resumes.single.arguments as Map)['captureId'], harness.leases[viewId]);
      expect(((resumes.single.arguments as Map)['configuration'] as Map).keys, isNot(contains('scanEnabled')));
      expect(harness.methods, isNot(contains('startScan')));
      await harness.event(viewId, 'behind popup');
      expect(values, isEmpty);

      navigator.currentState!.pop();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pump();
      expect(harness.methods, contains('startScan'));
      await harness.event(viewId, 'after popup');
      expect(values.single.rawValue, 'after popup');
      await close(tester);
    });

    testWidgets('invalid settings cannot prevent a popup from stopping recognition', (tester) async {
      final navigator = GlobalKey<NavigatorState>();
      final values = <Barcode>[];
      final errors = <Object>[];
      var zoom = 1.0;
      late StateSetter update;
      await tester.pumpWidget(
        MaterialApp(
          navigatorKey: navigator,
          home: StatefulBuilder(
            builder: (context, setState) {
              update = setState;
              return Scaffold(body: BarcodeScanner(zoomRatio: zoom, scanning: true, onScan: values.add, onError: errors.add));
            },
          ),
        ),
      );
      await tester.pump();
      final viewId = harness.leases.keys.single;
      update(() => zoom = 0);
      await tester.pump();
      expect(errors, contains(isArgumentError));
      harness.calls.clear();

      await openPopup(tester, navigator);

      expect(harness.methods, contains('cancelScan'));
      expect(harness.methods, isNot(contains('pauseCameraMethod')));
      expect(harness.methods, isNot(contains('closeCapture')));
      await harness.event(viewId, 'behind popup');
      expect(values, isEmpty);
      expect(harness.errors, isEmpty);
      await close(tester);
    });
  });

  group('warm pause', () {
    late RuntimeHarness harness;
    setUp(() => harness = RuntimeHarness());
    tearDown(() => harness.dispose());

    Widget page({bool paused = false, bool scanning = true, double zoom = 2, bool flash = true}) =>
        MaterialApp(home: BarcodeScanner(cameraPaused: paused, scanning: scanning, zoomRatio: zoom, flashEnabled: flash, onScan: (_) {}));

    Future<void> showPreview(WidgetTester tester) async {
      await harness.send(
        const MethodCall('onPreviewState', {
          'subscriptionId': 'preview',
          'description': {'textureId': 77, 'width': 1280, 'height': 720, 'rotationDegrees': 90, 'mirrored': false, 'state': 'streaming'},
        }),
      );
      await tester.pump();
    }

    Future<void> close(WidgetTester tester) async {
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(milliseconds: 300));
      await harness.dispose();
    }

    testWidgets('paused page takes the warm camera back before resuming after navigation', (tester) async {
      final navigator = GlobalKey<NavigatorState>();
      late StateSetter updatePage;
      var paused = false;
      final results = <Barcode>[];
      await tester.pumpWidget(
        MaterialApp(
          navigatorKey: navigator,
          home: StatefulBuilder(
            builder: (context, setState) {
              updatePage = setState;
              return Scaffold(body: BarcodeScanner(cameraPaused: paused, scanning: true, zoomRatio: 3, onScan: results.add));
            },
          ),
        ),
      );
      await tester.pump();
      await showPreview(tester);
      final firstView = harness.leases.keys.single;
      updatePage(() => paused = true);
      await tester.pump();
      await tester.pump();
      final pausedImage = tester.widget<RawImage>(find.byType(RawImage)).image;
      expect(pausedImage, isNotNull);
      unawaited(
        navigator.currentState!.push(
          MaterialPageRoute<void>(builder: (_) => Scaffold(body: BarcodeScanner(scanning: true, zoomRatio: 2, onScan: (_) {}))),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pump();
      harness.calls.clear();

      navigator.currentState!.pop();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pump();
      expect(harness.methods, isNot(contains('pauseCameraMethod')));
      final capture = harness.calls.singleWhere((call) => call.method == 'resumeCameraMethod');
      expect((capture.arguments as Map)['captureId'], harness.leases[firstView]);
      expect((capture.arguments as Map)['configuration'], containsPair('zoomRatio', 3.0));
      expect(harness.methods, isNot(contains('startScan')));
      expect(blackPlaceholder, findsNothing);
      expect(tester.widget<RawImage>(find.byType(RawImage)).image, same(pausedImage));
      await harness.event(firstView, 'paused result');
      expect(results, isEmpty);
      final returnedLease = harness.leases[firstView];
      harness.calls.clear();

      updatePage(() => paused = false);
      await tester.pump();
      expect(harness.methods, ['subscribeScan', 'startScan']);
      expect(harness.leases[firstView], returnedLease);
      expect(tester.widget<Texture>(find.byType(Texture)).freeze, isFalse);
      expect(blackPlaceholder, findsNothing);
      await harness.event(firstView, 'resumed result');
      expect(results.single.rawValue, 'resumed result');
      await close(tester);
    });

    testWidgets('manual pause freezes the texture and resumes without camera startup', (tester) async {
      await tester.pumpWidget(page());
      await tester.pump();
      await showPreview(tester);
      final textureElement = tester.element(find.byType(Texture));
      harness.calls.clear();

      await tester.pumpWidget(page(paused: true));
      await tester.pump();
      expect(find.byType(RawImage), findsOneWidget);
      expect(harness.methods, ['cancelScan']);
      expect(blackPlaceholder, findsNothing);
      await showPreview(tester);
      expect(find.byType(RawImage), findsOneWidget);

      await tester.pumpWidget(page());
      await tester.pump();
      final texture = tester.widget<Texture>(find.byType(Texture));
      expect(texture.freeze, isFalse);
      expect(texture.textureId, 77);
      expect(identical(tester.element(find.byType(Texture)), textureElement), isTrue);
      expect(harness.methods, ['cancelScan', 'subscribeScan', 'startScan']);
      expect(blackPlaceholder, findsNothing);
      await close(tester);
    });

    testWidgets('paused control changes retain the original image until resume', (tester) async {
      await tester.pumpWidget(page());
      await tester.pump();
      await showPreview(tester);
      final lease = harness.leases.values.single;
      await tester.pumpWidget(page(paused: true));
      await tester.pump();
      final pausedImage = tester.widget<RawImage>(find.byType(RawImage)).image;
      expect(pausedImage, isNotNull);
      harness.calls.clear();

      await tester.pumpWidget(page(paused: true, zoom: 3, flash: false));
      await tester.pump();
      expect(harness.methods, ['updateCameraSettings']);
      expect(harness.calls.first.arguments, containsPair('zoomRatio', 3.0));
      expect(harness.calls.single.arguments, containsPair('torchEnabled', false));
      expect(harness.leases.values.single, lease);
      await showPreview(tester);
      expect(tester.widget<RawImage>(find.byType(RawImage)).image, same(pausedImage));
      expect(blackPlaceholder, findsNothing);
      harness.calls.clear();

      await tester.pumpWidget(page(zoom: 3, flash: false));
      await tester.pump();
      expect(find.byType(RawImage), findsNothing);
      expect(tester.widget<Texture>(find.byType(Texture)).freeze, isFalse);
      expect(harness.methods, ['subscribeScan', 'startScan']);
      expect(harness.leases.values.single, lease);
      await close(tester);
    });

    testWidgets('pause without recognition still freezes preview without native controls', (tester) async {
      await tester.pumpWidget(page(scanning: false));
      await tester.pump();
      await showPreview(tester);
      harness.calls.clear();
      await tester.pumpWidget(page(paused: true, scanning: false));
      await tester.pump();
      expect(find.byType(RawImage), findsOneWidget);
      await tester.pumpWidget(page(scanning: false));
      await tester.pump();
      expect(tester.widget<Texture>(find.byType(Texture)).freeze, isFalse);
      expect(harness.calls, isEmpty);
      await close(tester);
    });

    testWidgets('backgrounding a manually paused scanner still stops the camera', (tester) async {
      await tester.pumpWidget(page());
      await tester.pump();
      await tester.pumpWidget(page(paused: true));
      await tester.pump();
      harness.calls.clear();
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      await tester.pump();
      expect(harness.methods, contains('pauseCameraMethod'));
      expect(harness.methods, contains('closeCapture'));
      harness.calls.clear();
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pump();
      expect(harness.methods, isNot(contains('openCapture')));
      await tester.pumpWidget(page());
      await tester.pump();
      expect(harness.methods, contains('openCapture'));
      await close(tester);
    });

    testWidgets('pausing during camera startup does not release or restart it', (tester) async {
      final start = Completer<void>();
      harness.handler = (call) async {
        if (call.method == 'resumeCameraMethod') await start.future;
        return null;
      };
      await tester.pumpWidget(page());
      await tester.pump();
      await tester.pumpWidget(page(paused: true));
      await tester.pump();
      start.complete();
      await tester.pump();
      await showPreview(tester);
      await tester.pump();
      expect(find.byType(RawImage), findsOneWidget);
      expect(harness.methods, isNot(contains('startScan')));
      expect(harness.methods, isNot(contains('closeCapture')));
      await tester.pumpWidget(page());
      await tester.pump();
      expect(harness.methods.where((method) => method == 'resumeCameraMethod'), hasLength(1));
      expect(harness.methods, contains('startScan'));
      await close(tester);
    });
  });

  group('focus feedback', () {
    late RuntimeHarness harness;
    late StateSetter updateScanner;
    var cameraPaused = false;
    CropRect crop = const CropRect(offsetY: -.2);
    setUp(() => harness = RuntimeHarness());
    tearDown(() => harness.dispose());

    final overlay = find.byType(ScannerOverlay);
    Object painting(WidgetTester tester) => tester.renderObject(find.descendant(of: overlay, matching: find.byType(CustomPaint)).first);

    Future<void> mount(WidgetTester tester, {TargetPlatform platform = TargetPlatform.android, bool reduceMotion = false}) async {
      cameraPaused = false;
      crop = const CropRect(offsetY: -.2);
      await tester.pumpWidget(
        MaterialApp(
          theme: ThemeData(platform: platform),
          builder: (context, child) => MediaQuery(data: MediaQuery.of(context).copyWith(disableAnimations: reduceMotion), child: child!),
          home: Center(
            child: SizedBox(
              width: 300,
              height: 400,
              child: StatefulBuilder(
                builder: (context, setState) {
                  updateScanner = setState;
                  return BarcodeScanner(cropRect: crop, cameraPaused: cameraPaused, onScan: (_) {});
                },
              ),
            ),
          ),
        ),
      );
      await tester.pump();
    }

    Future<void> close(WidgetTester tester) async {
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(milliseconds: 300));
      await harness.dispose();
    }

    testWidgets('locked focus circle fades away while the lock stays visible', (tester) async {
      await mount(tester);
      await tester.longPress(overlay);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      expect(painting(tester), paints..circle(x: 150, y: 160, color: Colors.white));
      await tester.pump(const Duration(milliseconds: 500));
      expect(painting(tester), isNot(paints..circle()));
      final focus = harness.calls.singleWhere((call) => call.method == 'focus');
      expect(focus.arguments, containsPair('locked', true));
      await close(tester);
    });

    testWidgets('lock leaves focus and settles in the upper left preview corner', (tester) async {
      await mount(tester);
      await tester.longPress(overlay);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 800));
      // Android's original 24dp icon frame starts at 0.8 * its size.
      expect(painting(tester), paints..translate(x: 19.2, y: 19.2));
      await close(tester);
    });

    testWidgets('another focus tap restarts the circle pulse before it finishes', (tester) async {
      await mount(tester);
      await tester.tap(overlay);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 250));
      expect(painting(tester), paints..circle(color: const Color(0x80ffffff)));
      await tester.tap(overlay);
      await tester.pump();
      expect(painting(tester), isNot(paints..circle()));
      await tester.pump(const Duration(milliseconds: 500));
      expect(painting(tester), paints..circle(color: Colors.white));
      await tester.pump(const Duration(milliseconds: 500));
      expect(painting(tester), isNot(paints..circle()));
      await close(tester);
    });

    for (final platform in [TargetPlatform.android, TargetPlatform.iOS]) {
      testWidgets('$platform lock fades in before flying to the corner', (tester) async {
        await mount(tester, platform: platform);
        final ios = platform == TargetPlatform.iOS;
        final initialX = ios ? 74.0 : 108.0;
        final target = ios ? 20.0 : 19.2;
        await tester.longPress(overlay);
        await tester.pump();
        await tester.pump(Duration(milliseconds: ios ? 250 : 300));
        expect(
          painting(tester),
          paints
            ..translate(x: initialX, y: 148)
            ..path(color: Colors.white),
        );
        await tester.pump(Duration(milliseconds: ios ? 375 : 250));
        expect(painting(tester), paints..translate(x: (initialX + target) / 2, y: (148 + target) / 2));
        await tester.pump(Duration(milliseconds: ios ? 375 : 250));
        expect(painting(tester), paints..translate(x: target, y: target));
        await close(tester);
      });
    }

    testWidgets('tap fades the corner lock out in 200ms and restores autofocus', (tester) async {
      await mount(tester);
      await tester.longPress(overlay);
      await tester.pump();
      await tester.pump(const Duration(seconds: 1));
      await tester.tap(overlay);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      expect(
        painting(tester),
        paints
          ..translate(x: 19.2, y: 19.2)
          ..path(color: const Color(0x80ffffff)),
      );
      await tester.pump(const Duration(milliseconds: 100));
      expect(painting(tester), isNot(paints..translate()));
      final focus = harness.calls.where((call) => call.method == 'focus');
      expect(focus.map((call) => (call.arguments as Map)['locked']), [true, false]);
      await close(tester);
    });

    testWidgets('repeated long press pulses the circle without moving the settled lock', (tester) async {
      await mount(tester);
      await tester.longPress(overlay);
      await tester.pump();
      await tester.pump(const Duration(seconds: 1));
      await tester.longPress(overlay);
      await tester.pump();
      expect(painting(tester), paints..translate(x: 19.2, y: 19.2));
      await tester.pump(const Duration(milliseconds: 500));
      expect(painting(tester), paints..circle(color: Colors.white));
      await tester.pump(const Duration(milliseconds: 500));
      expect(painting(tester), isNot(paints..circle()));
      await close(tester);
    });

    testWidgets('pause retains focus feedback and permits focus on the captured camera', (tester) async {
      await mount(tester);
      await tester.longPress(overlay);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      updateScanner(() => cameraPaused = true);
      await tester.pump();
      expect(painting(tester), paints..translate());
      final focusCalls = harness.methods.where((method) => method == 'focus').length;
      await tester.tap(overlay);
      await tester.pump();
      expect(harness.methods.where((method) => method == 'focus'), hasLength(focusCalls + 1));
      await tester.pump(const Duration(milliseconds: 500));
      expect(painting(tester), paints..circle(color: Colors.white));
      updateScanner(() => cameraPaused = false);
      await tester.pump();
      await tester.tap(overlay);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      expect(painting(tester), paints..circle(color: Colors.white));
      await close(tester);
    });

    testWidgets('reduced motion places the lock in the corner without flight', (tester) async {
      await mount(tester, reduceMotion: true);
      await tester.longPress(overlay);
      await tester.pump();
      expect(painting(tester), paints..translate(x: 19.2, y: 19.2));
      expect(painting(tester), isNot(paints..circle()));
      await tester.tap(overlay);
      await tester.pump();
      expect(painting(tester), isNot(paints..translate()));
      await close(tester);
    });

    testWidgets('tap during lock flight fades at its current position', (tester) async {
      await mount(tester);
      await tester.longPress(overlay);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 550));
      await tester.tap(overlay);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      expect(
        painting(tester),
        paints
          ..translate(x: 63.6, y: 83.6)
          ..path(color: const Color(0x80ffffff)),
      );
      await tester.pump(const Duration(seconds: 1));
      expect(painting(tester), isNot(paints..translate()));
      expect(painting(tester), isNot(paints..circle()));
      await close(tester);
    });

    testWidgets('crop movement keeps the lock in the corner and moves focus feedback', (tester) async {
      await mount(tester);
      await tester.longPress(overlay);
      await tester.pump();
      await tester.pump(const Duration(seconds: 1));
      updateScanner(() => crop = const CropRect(offsetX: .2, offsetY: .2));
      await tester.pump();
      expect(painting(tester), paints..translate(x: 19.2, y: 19.2));
      await tester.tap(overlay);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      expect(painting(tester), paints..circle(x: 180, y: 240, color: Colors.white));
      await close(tester);
    });

    testWidgets('backgrounding resets focus feedback before returning to the scanner', (tester) async {
      await mount(tester);
      await tester.longPress(overlay);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      await tester.pump();
      expect(painting(tester), isNot(paints..circle()));
      expect(painting(tester), isNot(paints..translate()));
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pump();
      expect(painting(tester), isNot(paints..translate()));
      await close(tester);
    });
  });
}
