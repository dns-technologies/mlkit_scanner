import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';
import 'package:mlkit_scanner/widgets/camera_preview.dart';
import 'package:mlkit_scanner/platform/ml_kit_channel.dart';
import 'package:mlkit_scanner/platform/scanner_runtime.dart';
import '../support/immediate_frame_test_binding.dart';

void main() {
  ImmediateFrameTestBinding();

  group('$BarcodeScanner', () {
    const channel = MethodChannel('mlkit_channel');
    final calls = <MethodCall>[];
    final pendingPlatformCreates = <Completer<Object?>>[];
    Future<void> sendNativeCall(MethodCall call) => _sendNativeCall(call);
    Completer<void>? captureCompletion;
    Completer<void>? cropCompletion;
    PlatformException? captureError;
    var completeCaptureOnRelease = true;
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

    setUpAll(() {
      messenger.setMockMethodCallHandler(SystemChannels.platform_views,
          (call) async {
        if (call.method != 'create') return null;
        // These tests explicitly deliver onCameraInitialized with chosen IDs.
        // Native factory registration itself is covered in camera_preview_test.
        final created = Completer<Object?>();
        pendingPlatformCreates.add(created);
        return created.future;
      });
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        if (call.method == 'pauseCameraMethod' &&
            completeCaptureOnRelease &&
            captureCompletion?.isCompleted == false) {
          if (!captureCompletion!.isCompleted) captureCompletion!.complete();
        }
        if (call.method == 'resumeCameraMethod') {
          await captureCompletion?.future;
          final error = captureError;
          captureError = null;
          if (error != null) throw error;
        }
        if (call.method == 'setCropAreaMethod') await cropCompletion?.future;
        return null;
      });
    });

    setUp(() {
      ScannerRuntime.instance = ScannerRuntime(MlKitChannel());
      calls.clear();
      captureCompletion = null;
      cropCompletion = null;
      captureError = null;
      completeCaptureOnRelease = true;
    });

    tearDownAll(() {
      messenger.setMockMethodCallHandler(SystemChannels.platform_views, null);
      messenger.setMockMethodCallHandler(channel, null);
    });

    Future<void> updateController(
        WidgetTester tester, Future<void> Function() action) async {
      await tester.runAsync(() async {
        await action();
        // Controller subscriptions were registered by native-view initialization
        // in runAsync's zone. Drain that zone's immediate SDK acknowledgements.
        await Future<void>.delayed(Duration.zero);
      });
      await tester.pump();
    }

    Future<void> waitForPreview(WidgetTester tester, BarcodeScannerController controller) async {
      // Route captures and native-view callbacks can use different test clocks.
      for (var attempt = 0; attempt < 10 && !controller.previewVisible.value; attempt++) {
        await tester.runAsync(() => Future<void>.delayed(Duration.zero));
        await tester.pump(const Duration(milliseconds: 1));
      }
      expect(controller.previewVisible.value, isTrue);
      await tester.pump();
    }

    void testScannerWidgets(String description, WidgetTesterCallback body) {
      testWidgets(description, (tester) async {
        final previousPlatform = debugDefaultTargetPlatformOverride;
        try {
          await body(tester);
        } finally {
          await tester.pumpWidget(const SizedBox.shrink());
          for (final created in pendingPlatformCreates) {
            created.complete(null);
          }
          pendingPlatformCreates.clear();
          await tester.pump(const Duration(milliseconds: 300));
          debugDefaultTargetPlatformOverride = previousPlatform;
        }
      });
    }

    for (final platform in [TargetPlatform.android, TargetPlatform.iOS]) {
      testScannerWidgets('covers the entire $platform preview until capture and crop complete', (tester) async {
        debugDefaultTargetPlatformOverride = platform;
        late BarcodeScannerController controller;
        captureCompletion = Completer<void>();
        cropCompletion = Completer<void>();
        final size = ValueNotifier(const Size(320, 240));
        addTearDown(size.dispose);
        await tester.pumpWidget(TestApp(
          child: ValueListenableBuilder<Size>(
            valueListenable: size,
            builder: (context, size, child) => SizedBox(width: size.width, height: size.height, child: child),
            child: BarcodeScanner(onScannerInitialized: (value) => controller = value, onScan: (_) {}),
          ),
        ));
        final previewFinder = find.byType(CameraPreview);
        final previewElement = tester.element(previewFinder);
        final cover = find.descendant(
          of: find.byType(BarcodeScanner),
          matching: find.byWidgetPredicate((widget) => widget is ColoredBox && widget.color == Colors.black),
        );
        expect(cover, findsOneWidget);
        expect(tester.getRect(cover), tester.getRect(previewFinder));
        late Future<void> initialization;
        await tester.runAsync(() async {
          initialization = _startCameraInitialization(tester.widget<CameraPreview>(previewFinder), 17);
        });
        await tester.pump();
        await updateController(tester, () => controller.setCropArea(const CropRect(scaleWidth: 0.5)));
        size.value = const Size(420, 200);
        await tester.pump();
        expect(tester.getSize(cover), const Size(420, 200));
        expect(tester.getRect(cover), tester.getRect(previewFinder));
        final coverRenderObject = tester.renderObject(cover);
        expect(tester.hitTestOnBinding(tester.getCenter(cover)).path.any((entry) => entry.target == coverRenderObject), isTrue);
        await tester.runAsync(() async {
          captureCompletion!.complete();
          await Future<void>.delayed(Duration.zero);
        });
        await tester.pump();
        expect(calls.last.method, 'setCropAreaMethod');
        expect(cover, findsOneWidget);
        await tester.runAsync(() async {
          cropCompletion!.complete();
          await initialization;
        });
        await tester.pump();
        expect(cover, findsNothing);
        expect(tester.element(previewFinder), same(previewElement));
        expect(pendingPlatformCreates, hasLength(1));
        captureCompletion = null;
        cropCompletion = null;
        await updateController(tester, controller.pauseCamera);
        expect(cover, findsNothing);
        expect(ScannerRuntime.instance.isCurrent(controller), isFalse);
        calls.clear();
        await updateController(tester, () => controller.setZoomRatio(3));
        await updateController(tester, controller.toggleFlash);
        expect(calls, isEmpty);

        captureCompletion = Completer<void>();
        await updateController(tester, controller.resumeCamera);
        expect(cover, findsOneWidget);
        expect(calls.map((call) => call.method), ['resumeCameraMethod']);
        expect((calls.single.arguments as Map)['configuration'],
            allOf(containsPair('zoomRatio', 3.0), containsPair('torchEnabled', true)));
        await tester.runAsync(() async {
          captureCompletion!.complete();
          await Future<void>.delayed(Duration.zero);
        });
        await waitForPreview(tester, controller);
        expect(cover, findsNothing);
        expect(tester.element(previewFinder), same(previewElement));
        expect(pendingPlatformCreates, hasLength(1));
      });
    }

    testScannerWidgets('initializes BarcodeScanner controller', (tester) async {
      BarcodeScannerController? controller;
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(
          onScannerInitialized: (value) => controller = value,
          onScan: (value) {},
        ),
      ));

      final preview =
          tester.firstWidget(find.byType(CameraPreview)) as CameraPreview;
      await _initializeCamera(tester, preview, 17);
      await tester.pumpAndSettle();

      expect(controller, isNotNull);
      expect(
        calls.firstWhere((call) => call.method == 'resumeCameraMethod').arguments,
        containsPair('viewId', 17),
      );
    });

    testScannerWidgets(
        'manual pause survives hiding and returning to the route',
        (tester) async {
      late BarcodeScannerController controller;
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(
          onScannerInitialized: (value) => controller = value,
          onScan: (_) {},
        ),
      ));
      final preview =
          tester.firstWidget<CameraPreview>(find.byType(CameraPreview));
      await _initializeCamera(tester, preview, 17);
      await updateController(tester, controller.pauseCamera);
      final navigator = tester.state<NavigatorState>(find.byType(Navigator));
      navigator.push<void>(
          MaterialPageRoute<void>(builder: (_) => const SizedBox.shrink()));
      await tester.pumpAndSettle();
      calls.clear();
      navigator.pop();
      await tester.pumpAndSettle();
      expect(calls, isEmpty);
      expect(controller.configuration.cameraPaused, isTrue);
      await updateController(tester, controller.resumeCamera);
      await waitForPreview(tester, controller);
      expect(calls.map((call) => call.method), ['resumeCameraMethod']);
    });

    testScannerWidgets('resuming a hidden paused scanner waits for its route to return', (tester) async {
      late BarcodeScannerController controller;
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(onScannerInitialized: (value) => controller = value, onScan: (_) {}),
      ));
      await _initializeCamera(tester, tester.widget<CameraPreview>(find.byType(CameraPreview)), 17);
      await updateController(tester, controller.pauseCamera);
      final navigator = tester.state<NavigatorState>(find.byType(Navigator));
      unawaited(navigator.push<void>(MaterialPageRoute<void>(builder: (_) => const SizedBox.shrink())));
      await tester.pumpAndSettle();
      calls.clear();

      await updateController(tester, controller.resumeCamera);
      await updateController(tester, () => controller.setZoomRatio(3));
      expect(calls, isEmpty);
      expect(ScannerRuntime.instance.isCurrent(controller), isFalse);

      navigator.pop();
      await tester.pumpAndSettle();
      await waitForPreview(tester, controller);
      expect(calls.map((call) => call.method), ['resumeCameraMethod']);
      expect((calls.single.arguments as Map)['configuration'], containsPair('zoomRatio', 3.0));
    });

    testScannerWidgets(
        'exposes controller before capture and retains an initialization crop',
        (tester) async {
      BarcodeScannerController? controller;
      captureCompletion = Completer<void>();
      const cropRect = CropRect(scaleWidth: 0.7, scaleHeight: 0.4);
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(
          onScannerInitialized: (value) {
            controller = value;
            unawaited(value.setCropArea(cropRect));
          },
          onScan: (_) {},
        ),
      ));
      final preview =
          tester.firstWidget(find.byType(CameraPreview)) as CameraPreview;

      late Future<void> initialization;
      await tester.runAsync(() async {
        initialization = _startCameraInitialization(preview, 17);
      });
      await tester.pump();
      await tester.pump();

      expect(controller, isNotNull);
      expect(
        calls.map((call) => call.method),
        ['resumeCameraMethod'],
      );
      expect(
        (calls.single.arguments as Map)['configuration'],
        containsPair('cropRect', cropRect.toJson()),
      );

      await tester.runAsync(() async {
        if (!captureCompletion!.isCompleted) captureCompletion!.complete();
        await initialization;
      });
      await tester.pumpAndSettle();
    });

    testScannerWidgets('disposing a widget does not cancel the shared scan',
        (tester) async {
      BarcodeScannerController? controller;
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(
          onScannerInitialized: (value) => controller = value,
          onScan: (value) {},
        ),
      ));
      final preview =
          tester.firstWidget(find.byType(CameraPreview)) as CameraPreview;
      await _initializeCamera(tester, preview, 17);
      await tester.pumpAndSettle();
      await updateController(tester, () => controller!.startScan(0));

      expect(
        calls.firstWhere((call) => call.method == 'startScan').arguments,
        {'type': 0, 'delay': 0},
      );

      await tester.pumpWidget(const TestApp(child: SizedBox.shrink()));
      await tester.pump();

      expect(calls.map((call) => call.method), isNot(contains('cancelScan')));
      expect(
        calls.firstWhere((call) => call.method == 'pauseCameraMethod').arguments,
        isNull,
      );
    });

    testScannerWidgets(
        'controller attaches its preview but does not address scanner controls',
        (tester) async {
      BarcodeScannerController? controller;
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(
          onScannerInitialized: (value) => controller = value,
          onScan: (value) {},
        ),
      ));
      final preview =
          tester.firstWidget(find.byType(CameraPreview)) as CameraPreview;
      await _initializeCamera(tester, preview, 17);
      await tester.pumpAndSettle();

      await updateController(tester, () => controller!.startScan(100));
      await updateController(tester, () => controller!.cancelScan());

      expect(calls.where((call) => call.method == 'startScan'), isNotEmpty);
      for (final call in calls) {
        if (call.method == 'resumeCameraMethod') {
          expect(call.arguments, containsPair('viewId', 17));
        } else if (call.method == 'startScan') {
          expect(call.arguments, isNot(contains('viewId')));
        } else if (call.method == 'pauseCameraMethod') {
          expect(call.arguments, isNull);
        }
      }
    });

    testScannerWidgets(
        'controller forwards retained configuration to its preview',
        (tester) async {
      BarcodeScannerController? controller;
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(
          onScannerInitialized: (value) => controller = value,
          onScan: (_) {},
        ),
      ));
      final preview =
          tester.firstWidget(find.byType(CameraPreview)) as CameraPreview;
      await _initializeCamera(tester, preview, 17);
      await tester.pumpAndSettle();
      calls.clear();

      await updateController(tester, () => controller!.setDelay(250));
      debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
      try {
        await updateController(
            tester,
            () => controller!.setIosCamera(
                  position: IosCameraPosition.front,
                  type: IosCameraType.builtInUltraWideCamera,
                ));
      } finally {
        debugDefaultTargetPlatformOverride = null;
      }

      await tester.pump();
      expect(calls, hasLength(3));
      expect(calls.map((call) => call.method),
          ['setScanDelay', 'pauseCameraMethod', 'resumeCameraMethod']);
      expect(calls[0].arguments, {'delay': 250});
      expect((calls[2].arguments as Map)['configuration'],
          containsPair('iosCamera', {'position': 2, 'type': 3}));
    });

    testScannerWidgets(
        'detached controller commands complete without native calls',
        (tester) async {
      BarcodeScannerController? controller;
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(
          onScannerInitialized: (value) => controller = value,
          onScan: (_) {},
        ),
      ));
      final preview =
          tester.firstWidget(find.byType(CameraPreview)) as CameraPreview;
      await _initializeCamera(tester, preview, 17);
      await tester.pumpAndSettle();

      await tester.pumpWidget(const TestApp(child: SizedBox.shrink()));
      await tester.pump();
      calls.clear();

      await controller!.toggleFlash();
      await controller!.startScan(100);
      await controller!.cancelScan();
      await controller!.setDelay(200);
      await controller!.setZoomRatio(2);
      await controller!.setCropArea(const CropRect(scaleWidth: 0.5));
      await controller!.setIosCamera(
        position: IosCameraPosition.back,
        type: IosCameraType.builtInWideAngleCamera,
      );

      expect(calls, isEmpty);
    });

    testScannerWidgets(
        'controller rejects invalid zoom before invoking native code',
        (tester) async {
      BarcodeScannerController? controller;
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(
          onScannerInitialized: (value) => controller = value,
          onScan: (_) {},
        ),
      ));
      final preview =
          tester.firstWidget(find.byType(CameraPreview)) as CameraPreview;
      await _initializeCamera(tester, preview, 17);
      await tester.pumpAndSettle();
      calls.clear();

      expect(() => controller!.setZoomRatio(0), throwsArgumentError);
      expect(calls, isEmpty);
    });

    testScannerWidgets('route visibility releases and recaptures the camera',
        (tester) async {
      late BarcodeScannerController controller;
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(
          onScannerInitialized: (value) => controller = value,
          onScan: (_) {},
        ),
      ));
      final preview =
          tester.firstWidget(find.byType(CameraPreview)) as CameraPreview;
      await _initializeCamera(tester, preview, 17);
      await tester.pumpAndSettle();
      calls.clear();

      final navigator = tester.state<NavigatorState>(find.byType(Navigator));
      navigator.push<void>(
        MaterialPageRoute<void>(builder: (_) => const SizedBox.shrink()),
      );
      await tester.pumpAndSettle();
      await tester.pump(const Duration(milliseconds: 1));

      expect(
        calls.where((call) => call.method == 'pauseCameraMethod').single.arguments,
        isNull,
      );

      calls.clear();
      navigator.pop();
      await tester.pumpAndSettle();
      await waitForPreview(tester, controller);

      expect(
        calls.where((call) => call.method == 'resumeCameraMethod').single.arguments,
        containsPair('viewId', 17),
      );
    });

    testScannerWidgets('popup routes keep the camera captured', (tester) async {
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(
          onScannerInitialized: (_) {},
          onScan: (_) {},
        ),
      ));
      final preview =
          tester.firstWidget(find.byType(CameraPreview)) as CameraPreview;
      await _initializeCamera(tester, preview, 17);
      await tester.pumpAndSettle();
      calls.clear();

      final scannerContext = tester.element(find.byType(BarcodeScanner));
      final navigator = Navigator.of(scannerContext);
      showDialog<void>(
        context: scannerContext,
        builder: (_) => const AlertDialog(content: Text('Dialog')),
      );
      await tester.pumpAndSettle();

      expect(
        calls.where((call) => call.method == 'pauseCameraMethod'),
        isEmpty,
      );

      navigator.pop();
      await tester.pumpAndSettle();

      expect(
        calls.where((call) => call.method == 'resumeCameraMethod'),
        isEmpty,
      );
    });

    testScannerWidgets('backgrounding releases the camera below a popup and waits for foreground to restore it', (tester) async {
      late BarcodeScannerController controller;
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(onScannerInitialized: (value) => controller = value, onScan: (_) {}),
      ));
      await _initializeCamera(tester, tester.widget<CameraPreview>(find.byType(CameraPreview)), 11);
      final context = tester.element(find.byType(BarcodeScanner));
      final navigator = Navigator.of(context);
      unawaited(showDialog<void>(context: context, builder: (_) => const AlertDialog(content: Text('Dialog'))));
      await tester.pumpAndSettle();
      calls.clear();
      addTearDown(() => tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed));

      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      await tester.pumpAndSettle();
      navigator.pop();
      await tester.pumpAndSettle();

      expect(calls.map((call) => call.method), ['pauseCameraMethod']);
      expect(controller.previewVisible.value, isFalse);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();

      expect(calls.map((call) => call.method), ['pauseCameraMethod', 'resumeCameraMethod']);
      expect(controller.previewVisible.value, isTrue);
    });

    for (final bottomSheet in [false, true]) {
      for (final paused in [false, true]) {
        testScannerWidgets(
            '${bottomSheet ? 'bottom sheet' : 'dialog'} returns camera ownership to the underlying scanner (paused: $paused)',
            (tester) async {
          late BarcodeScannerController firstController;
          late BarcodeScannerController secondController;
          const firstKey = ValueKey('first-scanner');
          const secondKey = ValueKey('modal-scanner');
          await tester.pumpWidget(TestApp(
            child: BarcodeScanner(key: firstKey, onScannerInitialized: (value) => firstController = value, onScan: (_) {}),
          ));
          await _initializeCamera(tester, tester.widget<CameraPreview>(find.byType(CameraPreview)), 11);
          if (paused) await updateController(tester, firstController.pauseCamera);
          final context = tester.element(find.byKey(firstKey));
          final navigator = Navigator.of(context);
          Widget modal(BuildContext context) => SizedBox(
                height: 200,
                child: BarcodeScanner(key: secondKey, onScannerInitialized: (value) => secondController = value, onScan: (_) {}),
              );
          if (bottomSheet) {
            unawaited(showModalBottomSheet<void>(context: context, builder: modal));
          } else {
            unawaited(showDialog<void>(context: context, builder: (context) => Dialog(child: modal(context))));
          }
          await tester.pumpAndSettle();
          await _initializeCamera(
            tester,
            tester.widget<CameraPreview>(find.descendant(of: find.byKey(secondKey), matching: find.byType(CameraPreview))),
            22,
          );
          await tester.pumpAndSettle();
          expect(firstController.previewVisible.value, paused);
          expect(secondController.previewVisible.value, isTrue);
          await updateController(tester, () => firstController.setZoomRatio(3));
          calls.clear();

          navigator.pop();
          await tester.pumpAndSettle();

          if (paused) {
            expect(calls.where((call) => call.method == 'resumeCameraMethod'), isEmpty);
            expect(firstController.configuration.cameraPaused, isTrue);
            expect(firstController.previewVisible.value, isTrue);
            await updateController(tester, firstController.resumeCamera);
          }
          await waitForPreview(tester, firstController);
          final restored = calls.singleWhere((call) => call.method == 'resumeCameraMethod');
          expect((restored.arguments as Map)['viewId'], 11);
          expect((restored.arguments as Map)['configuration'], containsPair('zoomRatio', 3.0));
          expect(ScannerRuntime.instance.isCurrent(firstController), isTrue);
          expect(firstController.previewVisible.value, isTrue);
          expect(find.byKey(secondKey), findsNothing);
        });
      }
    }

    testScannerWidgets('nested dialogs restore the nearest underlying scanner', (tester) async {
      final controllers = <int, BarcodeScannerController>{};
      Widget scanner(int id) => SizedBox(
            height: 200,
            child: BarcodeScanner(key: ValueKey(id), onScannerInitialized: (value) => controllers[id] = value, onScan: (_) {}),
          );
      CameraPreview preview(int id) => tester.widget<CameraPreview>(
            find.descendant(of: find.byKey(ValueKey(id)), matching: find.byType(CameraPreview)),
          );
      await tester.pumpWidget(TestApp(child: scanner(11)));
      await _initializeCamera(tester, preview(11), 11);
      final navigator = Navigator.of(tester.element(find.byKey(const ValueKey(11))));
      for (final id in [22, 33]) {
        unawaited(showDialog<void>(context: navigator.context, builder: (_) => Dialog(child: scanner(id))));
        await tester.pumpAndSettle();
        await _initializeCamera(tester, preview(id), id);
        await tester.pumpAndSettle();
      }
      for (final id in [22, 11]) {
        calls.clear();
        navigator.pop();
        await tester.pumpAndSettle();

        expect(calls.singleWhere((call) => call.method == 'resumeCameraMethod').arguments, containsPair('viewId', id));
        expect(ScannerRuntime.instance.isCurrent(controllers[id]!), isTrue);
        expect(controllers[id]!.previewVisible.value, isTrue);
        if (id == 22) expect(controllers[11]!.previewVisible.value, isFalse);
      }
    });

    testScannerWidgets('closing a dialog before its capture completes restores the underlying scanner', (tester) async {
      late BarcodeScannerController firstController;
      late BarcodeScannerController secondController;
      const modalKey = ValueKey('modal-scanner');
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(onScannerInitialized: (value) => firstController = value, onScan: (_) {}),
      ));
      await _initializeCamera(tester, tester.widget<CameraPreview>(find.byType(CameraPreview)), 11);
      final context = tester.element(find.byType(BarcodeScanner));
      final navigator = Navigator.of(context);
      unawaited(showDialog<void>(
        context: context,
        builder: (_) => Dialog(
          child: SizedBox(
            height: 200,
            child: BarcodeScanner(key: modalKey, onScannerInitialized: (value) => secondController = value, onScan: (_) {}),
          ),
        ),
      ));
      await tester.pumpAndSettle();
      final pending = captureCompletion = Completer<void>();
      completeCaptureOnRelease = false;
      late Future<void> initialization;
      await tester.runAsync(() async {
        initialization = _startCameraInitialization(
          tester.widget<CameraPreview>(find.descendant(of: find.byKey(modalKey), matching: find.byType(CameraPreview))),
          22,
        );
        await Future<void>.delayed(Duration.zero);
      });
      expect(secondController.previewVisible.value, isFalse);
      captureCompletion = null;
      calls.clear();

      navigator.pop();
      await tester.pumpAndSettle();

      expect(calls.singleWhere((call) => call.method == 'resumeCameraMethod').arguments, containsPair('viewId', 11));
      expect(firstController.previewVisible.value, isTrue);
      calls.clear();
      await tester.runAsync(() async {
        pending.complete();
        await initialization;
      });
      await tester.pumpAndSettle();
      expect(ScannerRuntime.instance.isCurrent(firstController), isTrue);
      expect(firstController.previewVisible.value, isTrue);
      expect(calls, isEmpty);
      expect(tester.takeException(), isNull);
    });

    testScannerWidgets('closing a dialog without a scanner does not restart an unfinished capture', (tester) async {
      late BarcodeScannerController controller;
      final pending = captureCompletion = Completer<void>();
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(onScannerInitialized: (value) => controller = value, onScan: (_) {}),
      ));
      late Future<void> initialization;
      await tester.runAsync(() async {
        initialization = _startCameraInitialization(tester.widget<CameraPreview>(find.byType(CameraPreview)), 11);
        await Future<void>.delayed(Duration.zero);
      });
      final context = tester.element(find.byType(BarcodeScanner));
      final navigator = Navigator.of(context);
      unawaited(showDialog<void>(context: context, builder: (_) => const AlertDialog(content: Text('Dialog'))));
      await tester.pumpAndSettle();
      navigator.pop();
      await tester.pumpAndSettle();

      expect(calls.map((call) => call.method), ['resumeCameraMethod']);
      expect(controller.previewVisible.value, isFalse);
      await tester.runAsync(() async {
        pending.complete();
        await initialization;
      });
      await tester.pumpAndSettle();
      expect(controller.previewVisible.value, isTrue);
    });

    testScannerWidgets(
        'A B A restores A settings and routes native events to the current controller',
        (tester) async {
      BarcodeScannerController? firstController;
      BarcodeScannerController? secondController;
      final firstScans = <String>[];
      final secondScans = <String>[];
      final firstTorchEvents = <bool>[];
      final secondTorchEvents = <bool>[];
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(
          onScannerInitialized: (controller) => firstController = controller,
          onScan: (barcode) => firstScans.add(barcode.rawValue),
          onChangeFlashState: firstTorchEvents.add,
        ),
      ));
      final firstPreview =
          tester.firstWidget(find.byType(CameraPreview)) as CameraPreview;
      await _initializeCamera(tester, firstPreview, 11);
      await tester.pumpAndSettle();

      final navigator = tester.state<NavigatorState>(find.byType(Navigator));
      navigator.push<void>(
        MaterialPageRoute<void>(
          builder: (_) => BarcodeScanner(
            onScannerInitialized: (controller) => secondController = controller,
            onScan: (barcode) => secondScans.add(barcode.rawValue),
            onChangeFlashState: secondTorchEvents.add,
          ),
        ),
      );
      await tester.pump();
      final secondPreview = tester
          .widgetList<CameraPreview>(
            find.byType(CameraPreview, skipOffstage: false),
          )
          .singleWhere((preview) => preview.onCameraInitialized != firstPreview.onCameraInitialized);
      await _initializeCamera(tester, secondPreview, 22);
      await tester.pumpAndSettle();
      calls.clear();

      await firstController!.setZoomRatio(2.0);
      await firstController!.startScan(100);
      await updateController(tester, () => secondController!.setZoomRatio(3.0));

      expect(calls.single.method, 'setZoomRatio');
      expect(calls.single.arguments, {'value': 3.0});

      await sendNativeCall(const MethodCall('onScanResult', {
        'viewId': 11,
        'barcode': {
          'raw_value': 'stale-a',
          'display_value': 'stale-a',
          'format': 1,
          'value_type': 7,
        },
      }));
      await updateController(tester, () => secondController!.startScan(0));
      await sendNativeCall(const MethodCall('onScanResult', {
        'viewId': 22,
        'barcode': {
          'raw_value': 'active-b',
          'display_value': 'active-b',
          'format': 1,
          'value_type': 7,
        },
      }));
      await sendNativeCall(const MethodCall('changeTorchStateMethod', {
        'viewId': 11,
        'value': true,
      }));
      await sendNativeCall(const MethodCall('changeTorchStateMethod', {
        'viewId': 22,
        'value': true,
      }));
      await tester.pump();

      expect(firstScans, isEmpty);
      expect(secondScans, ['active-b']);
      expect(firstTorchEvents, isEmpty);
      expect(secondTorchEvents, [true]);

      calls.clear();
      navigator.pop();
      await tester.pumpAndSettle();
      await tester.runAsync(() => firstController!.setZoomRatio(2.0));
      final restored =
          calls.singleWhere((call) => call.method == 'resumeCameraMethod');
      expect((restored.arguments as Map)['viewId'], 11);
      expect((restored.arguments as Map)['configuration'], {
        'zoomRatio': 2.0,
        'torchEnabled': false,
        'cropRect': null,
        'scanEnabled': true,
        'scanDelay': 100,
      });
      calls.clear();
      await secondController!.setZoomRatio(9);
      expect(calls, isEmpty);
      await sendNativeCall(const MethodCall('onScanResult', {
        'viewId': 22,
        'barcode': {
          'raw_value': 'stale-b',
          'display_value': 'stale-b',
          'format': 1,
          'value_type': 7,
        },
      }));
      await sendNativeCall(const MethodCall('onScanResult', {
        'viewId': 11,
        'barcode': {
          'raw_value': 'returned-a',
          'display_value': 'returned-a',
          'format': 1,
          'value_type': 7,
        },
      }));
      await tester.pump();
      expect(firstScans, ['returned-a']);
      expect(secondScans, ['active-b']);
    });

    testScannerWidgets(
        'fast A B without return completes A initialization and exposes controller',
        (tester) async {
      BarcodeScannerController? controller;
      final initializationErrors = <PlatformException>[];
      captureCompletion = Completer<void>();
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(
          onScannerInitialized: (value) => controller = value,
          onCameraInitializeError: initializationErrors.add,
          onScan: (_) {},
        ),
      ));
      final preview =
          tester.firstWidget(find.byType(CameraPreview)) as CameraPreview;

      late Future<void> initialization;
      await tester.runAsync(() async {
        initialization = _startCameraInitialization(preview, 17);
      });
      await tester.pump();
      expect(
        calls.where((call) => call.method == 'resumeCameraMethod'),
        hasLength(1),
      );

      final navigator = tester.state<NavigatorState>(find.byType(Navigator));
      navigator.push<void>(
        MaterialPageRoute<void>(builder: (_) => const SizedBox.shrink()),
      );
      await tester.pumpAndSettle();
      await tester.runAsync(() async {
        if (!captureCompletion!.isCompleted) captureCompletion!.complete();
        await initialization;
      });
      await tester.pumpAndSettle();

      expect(initializationErrors, isEmpty);
      expect(controller, isNotNull);
      expect(tester.takeException(), isNull);

      calls.clear();
      await controller!.setZoomRatio(2.0);
      await controller!.toggleFlash();
      await controller!.setCropArea(const CropRect(scaleWidth: 0.5));
      await controller!.startScan(250);

      expect(calls, isEmpty);
      expect(
        calls.where((call) => call.method == 'resumeCameraMethod'),
        isEmpty,
      );
    });

    testScannerWidgets(
        'A B A publishes controller once while capture is pending',
        (tester) async {
      BarcodeScannerController? controller;
      var initializationCount = 0;
      final initializationErrors = <PlatformException>[];
      captureCompletion = Completer<void>();
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(
          onScannerInitialized: (value) {
            controller = value;
            initializationCount += 1;
          },
          onCameraInitializeError: initializationErrors.add,
          onScan: (_) {},
        ),
      ));
      final preview =
          tester.firstWidget(find.byType(CameraPreview)) as CameraPreview;
      late Future<void> initialization;
      await tester.runAsync(() async {
        initialization = _startCameraInitialization(preview, 17);
      });
      await tester.pump();

      final navigator = tester.state<NavigatorState>(find.byType(Navigator));
      navigator.push<void>(
        MaterialPageRoute<void>(builder: (_) => const SizedBox.shrink()),
      );
      await tester.pumpAndSettle();
      navigator.pop();
      await tester.pumpAndSettle();
      await tester.runAsync(() => initialization);

      expect(
        calls.where((call) => call.method == 'resumeCameraMethod'),
        hasLength(2),
      );
      expect(controller, isNotNull);
      expect(initializationCount, 1);

      if (!captureCompletion!.isCompleted) captureCompletion!.complete();
      await tester.pumpAndSettle();

      expect(initializationErrors, isEmpty);
      expect(initializationCount, 1);
      expect(controller, isNotNull);
      expect(tester.takeException(), isNull);
    });

    testScannerWidgets(
        'covered state stays in Dart and restores as one snapshot',
        (tester) async {
      BarcodeScannerController? controller;
      await tester.pumpWidget(TestApp(
          child: BarcodeScanner(
        onScannerInitialized: (value) => controller = value,
        onScan: (_) {},
      )));
      await _initializeCamera(
        tester,
        tester.widget<CameraPreview>(find.byType(CameraPreview)),
        17,
      );
      await tester.pumpAndSettle();
      final navigator = tester.state<NavigatorState>(find.byType(Navigator));
      navigator.push<void>(
          MaterialPageRoute<void>(builder: (_) => const SizedBox.shrink()));
      await tester.pumpAndSettle();
      calls.clear();

      for (var value = 1; value <= 100; value++) {
        await controller!.setZoomRatio(value.toDouble());
      }
      await controller!.toggleFlash();
      await controller!.setCropArea(const CropRect(scaleWidth: 0.4));
      await controller!.startScan(450);
      expect(calls, isEmpty);

      navigator.pop();
      await tester.pumpAndSettle();
      final capture =
          calls.singleWhere((call) => call.method == 'resumeCameraMethod');
      expect((capture.arguments as Map)['configuration'], {
        'zoomRatio': 100.0,
        'torchEnabled': true,
        'cropRect': const CropRect(scaleWidth: 0.4).toJson(),
        'scanEnabled': true,
        'scanDelay': 450,
      });
      expect(calls.where((call) => call.method != 'resumeCameraMethod'), isEmpty);
    });

    testScannerWidgets(
        'native scans wait until the recaptured preview is ready',
        (tester) async {
      BarcodeScannerController? controller;
      final results = <Barcode>[];
      await tester.pumpWidget(TestApp(
          child: BarcodeScanner(
        onScannerInitialized: (value) => controller = value,
        onScan: results.add,
      )));
      await _initializeCamera(
        tester,
        tester.widget<CameraPreview>(find.byType(CameraPreview)),
        17,
      );
      await tester.pumpAndSettle();
      await updateController(tester, () => controller!.startScan(0));
      await updateController(
          tester, () => ScannerRuntime.instance.release(controller!));
      captureCompletion = Completer<void>();
      late Future<void> resumed;
      await tester.runAsync(() async {
        resumed = ScannerRuntime.instance.capture(controller!);
      });
      await tester.pump();
      const event = MethodCall('onScanResult', {
        'viewId': 17,
        'barcode': {
          'raw_value': 'old',
          'display_value': 'old',
          'format': 1,
          'value_type': 1
        },
      });

      await sendNativeCall(event);
      await tester.pump();
      expect(results, isEmpty);
      await tester.runAsync(() async {
        captureCompletion!.complete();
        await resumed;
      });
      await tester.pumpAndSettle();
      await sendNativeCall(event);
      await tester.pump();
      expect(results, hasLength(1));
    });

    testScannerWidgets('active configuration update retries a failed capture',
        (tester) async {
      BarcodeScannerController? controller;
      final errors = <PlatformException>[];
      captureCompletion = Completer<void>();
      await tester.pumpWidget(TestApp(
          child: BarcodeScanner(
        onScannerInitialized: (value) => controller = value,
        onCameraInitializeError: errors.add,
        onScan: (_) {},
      )));
      late Future<void> initialization;
      await tester.runAsync(() async {
        initialization = _startCameraInitialization(
          tester.widget<CameraPreview>(find.byType(CameraPreview)),
          17,
        );
      });
      await tester.pump();
      await tester.runAsync(() async {
        captureError = PlatformException(
          code: '6',
          message: 'permission denied',
        );
        captureCompletion!.complete();
        await initialization;
      });
      await tester.pumpAndSettle();
      expect(errors, hasLength(1));

      captureCompletion = null;
      await updateController(tester,
          () => controller!.setZoomRatio(controller!.configuration.zoomRatio));
      await tester.pumpAndSettle();
      expect(
          calls.where((call) => call.method == 'resumeCameraMethod'), hasLength(2));
      expect(tester.takeException(), isNull);
    });

    testScannerWidgets(
        'foreground recaptures latest state and background setters stay local',
        (tester) async {
      BarcodeScannerController? controller;
      await tester.pumpWidget(TestApp(
          child: BarcodeScanner(
        onScannerInitialized: (value) => controller = value,
        onScan: (_) {},
      )));
      await _initializeCamera(
        tester,
        tester.widget<CameraPreview>(find.byType(CameraPreview)),
        17,
      );
      await tester.pumpAndSettle();
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      await tester.pumpAndSettle();
      expect(calls.last.method, 'pauseCameraMethod');
      calls.clear();
      await controller!.setZoomRatio(2.5);
      expect(calls, isEmpty);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(calls.single.method, 'resumeCameraMethod');
      expect((calls.single.arguments as Map)['configuration'],
          containsPair('zoomRatio', 2.5));
    });

    testScannerWidgets(
        'invalid delay cannot poison retained Dart configuration',
        (tester) async {
      BarcodeScannerController? controller;
      await tester.pumpWidget(TestApp(
          child: BarcodeScanner(
        onScannerInitialized: (value) => controller = value,
        onScan: (_) {},
      )));
      await _initializeCamera(
        tester,
        tester.widget<CameraPreview>(find.byType(CameraPreview)),
        17,
      );
      await tester.pumpAndSettle();
      calls.clear();
      for (final delay in [-1, 0x80000000]) {
        expect(() => controller!.startScan(delay), throwsArgumentError);
        expect(() => controller!.setDelay(delay), throwsArgumentError);
      }
      expect(calls, isEmpty);
      await expectLater(
          controller!.setIosCamera(
              position: IosCameraPosition.back,
              type: IosCameraType.builtInWideAngleCamera),
          throwsUnsupportedError);
      expect(calls, isEmpty);
      await updateController(tester,
          () => controller!.setZoomRatio(controller!.configuration.zoomRatio));
      expect(calls, isEmpty);
      expect(controller!.configuration.scanDelay, 0);
      expect(controller!.configuration.scanEnabled, false);
    });

    testScannerWidgets('initialization error is ignored after route is covered',
        (tester) async {
      BarcodeScannerController? controller;
      final initializationErrors = <PlatformException>[];
      captureCompletion = Completer<void>();
      completeCaptureOnRelease = false;
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(
          onScannerInitialized: (value) => controller = value,
          onCameraInitializeError: initializationErrors.add,
          onScan: (_) {},
        ),
      ));
      final preview =
          tester.firstWidget(find.byType(CameraPreview)) as CameraPreview;

      late Future<void> initialization;
      await tester.runAsync(() async {
        initialization = _startCameraInitialization(preview, 17);
      });
      await tester.pump();

      final navigator = tester.state<NavigatorState>(find.byType(Navigator));
      navigator.push<void>(
        MaterialPageRoute<void>(builder: (_) => const SizedBox.shrink()),
      );
      await tester.pumpAndSettle();
      final error = PlatformException(
        code: CameraControlException.errorCode,
        message: 'Camera control operation failed',
        details: const {
          'operation': 'torch',
          'viewId': 17,
        },
      );
      await tester.runAsync(() async {
        captureError = error;
        captureCompletion!.complete();
        await initialization;
      });
      await tester.pumpAndSettle();

      expect(initializationErrors, isEmpty);
      expect(controller, isNotNull);
      expect(tester.takeException(), isNull);
    });
  });
}

class TestApp extends StatelessWidget {
  final Widget? child;

  const TestApp({
    this.child,
    Key? key,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        body: child,
      ),
    );
  }
}

Future<void> _initializeCamera(
  WidgetTester tester,
  CameraPreview preview,
  int viewId,
) async {
  await tester.runAsync(() => _startCameraInitialization(preview, viewId));
}

Future<void> _startCameraInitialization(
  CameraPreview preview,
  int viewId,
) {
  final initialization =
      Future<void>.sync(() => preview.onCameraInitialized(viewId));
  unawaited(
    initialization.then<void>((_) {}, onError: (Object _, StackTrace __) {}),
  );
  return initialization;
}

Future<void> _sendNativeCall(MethodCall call) async {
  final completed = Completer<void>();
  final data = const StandardMethodCodec().encodeMethodCall(call);
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .handlePlatformMessage(
    'mlkit_channel',
    data,
    (_) => completed.complete(),
  );
  await completed.future;
}
