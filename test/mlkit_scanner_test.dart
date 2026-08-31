import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';
import 'package:mlkit_scanner/widgets/camera_preview.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('MLKit scanner', () {
    const channel = MethodChannel('mlkit_channel');
    final calls = <MethodCall>[];
    Completer<void>? captureCompletion;
    final captureCompletions = <int, Completer<void>>{};
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

    setUpAll(() {
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        if (call.method == 'captureCamera') {
          final arguments = Map<Object?, Object?>.from(call.arguments as Map);
          final viewCompletion = captureCompletions[arguments['viewId']];
          await (viewCompletion ?? captureCompletion)?.future;
        }
        return null;
      });
    });

    setUp(() {
      calls.clear();
      captureCompletion = null;
      captureCompletions.clear();
    });

    tearDownAll(() {
      messenger.setMockMethodCallHandler(channel, null);
    });

    testWidgets('initializes BarcodeScanner controller', (tester) async {
      BarcodeScannerController? controller;
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(
          onScannerInitialized: (value) => controller = value,
          onScan: (value) {},
        ),
      ));

      final preview =
          tester.firstWidget(find.byType(CameraPreview)) as CameraPreview;
      preview.onCameraInitialized(17);
      await tester.pumpAndSettle();

      expect(controller, isNotNull);
      expect(
        calls.firstWhere((call) => call.method == 'captureCamera').arguments,
        {'viewId': 17},
      );
    });

    testWidgets('disposing a widget does not cancel the shared scan',
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
      preview.onCameraInitialized(17);
      await tester.pump();
      await controller!.startScan(0);

      expect(
        calls.firstWhere((call) => call.method == 'startScan').arguments,
        {
          'viewId': 17,
          'type': 0,
          'delay': 0,
        },
      );

      await tester.pumpWidget(const TestApp(child: SizedBox.shrink()));
      await tester.pump();

      expect(calls.map((call) => call.method), isNot(contains('cancelScan')));
      expect(
        calls.firstWhere((call) => call.method == 'releaseCamera').arguments,
        {'viewId': 17},
      );
    });

    testWidgets('controller addresses lifecycle commands to its preview',
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
      preview.onCameraInitialized(17);
      await tester.pump();

      await controller!.startScan(100);
      await controller!.cancelScan();
      await controller!.pauseCamera();
      await controller!.resumeCamera();

      for (final method in <String>[
        'startScan',
        'cancelScan',
        'pauseCameraMethod',
        'resumeCameraMethod',
      ]) {
        expect(
          calls.firstWhere((call) => call.method == method).arguments,
          containsPair('viewId', 17),
        );
      }
    });

    testWidgets('route visibility releases and recaptures the camera',
        (tester) async {
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(
          onScannerInitialized: (_) {},
          onScan: (_) {},
        ),
      ));
      final preview =
          tester.firstWidget(find.byType(CameraPreview)) as CameraPreview;
      preview.onCameraInitialized(17);
      await tester.pumpAndSettle();
      calls.clear();

      final navigator = tester.state<NavigatorState>(find.byType(Navigator));
      navigator.push<void>(
        MaterialPageRoute<void>(builder: (_) => const SizedBox.shrink()),
      );
      await tester.pumpAndSettle();

      expect(
        calls.where((call) => call.method == 'releaseCamera').single.arguments,
        {'viewId': 17},
      );

      calls.clear();
      navigator.pop();
      await tester.pumpAndSettle();

      expect(
        calls.where((call) => call.method == 'captureCamera').single.arguments,
        {'viewId': 17},
      );
    });

    testWidgets('popup routes keep the camera captured', (tester) async {
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(
          onScannerInitialized: (_) {},
          onScan: (_) {},
        ),
      ));
      final preview =
          tester.firstWidget(find.byType(CameraPreview)) as CameraPreview;
      preview.onCameraInitialized(17);
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
        calls.where((call) => call.method == 'releaseCamera'),
        isEmpty,
      );

      navigator.pop();
      await tester.pumpAndSettle();

      expect(
        calls.where((call) => call.method == 'captureCamera'),
        isEmpty,
      );
    });

    testWidgets(
        'rapid A B A with scanners recaptures the original A before ticker changes',
        (tester) async {
      var firstInitializationCount = 0;
      await tester.pumpWidget(TestApp(
        child: BarcodeScanner(
          onScannerInitialized: (_) => firstInitializationCount += 1,
          onScan: (_) {},
        ),
      ));
      final firstPreview =
          tester.firstWidget(find.byType(CameraPreview)) as CameraPreview;
      firstPreview.onCameraInitialized(11);
      await tester.pumpAndSettle();
      calls.clear();

      captureCompletions[22] = Completer<void>();
      final navigator = tester.state<NavigatorState>(find.byType(Navigator));
      navigator.push<void>(
        MaterialPageRoute<void>(
          builder: (_) => BarcodeScanner(
            onScannerInitialized: (_) {},
            onScan: (_) {},
          ),
        ),
      );
      await tester.pump();
      final secondPreview = tester
          .widgetList<CameraPreview>(
            find.byType(CameraPreview, skipOffstage: false),
          )
          .singleWhere((preview) => !identical(preview, firstPreview));
      secondPreview.onCameraInitialized(22);
      await tester.pump();

      navigator.pop();
      await tester.pumpAndSettle();

      expect(
        calls
            .where((call) => call.method == 'captureCamera')
            .map((call) => (call.arguments as Map)['viewId']),
        [22, 11],
      );
      expect(
        calls
            .where((call) => call.method == 'releaseCamera')
            .map((call) => (call.arguments as Map)['viewId']),
        contains(22),
      );
      expect(firstInitializationCount, 1);

      captureCompletions[22]!.complete();
      await tester.pump();
      expect(tester.takeException(), isNull);
    });

    testWidgets('commands from covered A remain addressed to A while B stays',
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
      firstPreview.onCameraInitialized(11);
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
          .singleWhere((preview) => !identical(preview, firstPreview));
      secondPreview.onCameraInitialized(22);
      await tester.pumpAndSettle();
      calls.clear();

      await firstController!.setZoom(0.25);
      await firstController!.startScan(100);
      await secondController!.setZoom(0.75);

      expect(
        calls.map((call) => call.arguments),
        [
          {'viewId': 11, 'value': 0.25},
          {
            'viewId': 11,
            'type': 0,
            'delay': 100,
          },
          {'viewId': 22, 'value': 0.75},
        ],
      );

      await _sendNativeCall(const MethodCall('onScanResult', {
        'viewId': 11,
        'barcode': {
          'raw_value': 'stale-a',
          'display_value': 'stale-a',
          'format': 1,
          'value_type': 7,
        },
      }));
      await _sendNativeCall(const MethodCall('onScanResult', {
        'viewId': 22,
        'barcode': {
          'raw_value': 'active-b',
          'display_value': 'active-b',
          'format': 1,
          'value_type': 7,
        },
      }));
      await _sendNativeCall(const MethodCall('changeTorchStateMethod', {
        'viewId': 11,
        'value': true,
      }));
      await _sendNativeCall(const MethodCall('changeTorchStateMethod', {
        'viewId': 22,
        'value': true,
      }));
      await tester.pump();

      expect(firstScans, isEmpty);
      expect(secondScans, ['active-b']);
      expect(firstTorchEvents, isEmpty);
      expect(secondTorchEvents, [true]);
    });

    testWidgets(
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

      preview.onCameraInitialized(17);
      await tester.pump();
      expect(
        calls.where((call) => call.method == 'captureCamera'),
        hasLength(1),
      );

      final navigator = tester.state<NavigatorState>(find.byType(Navigator));
      navigator.push<void>(
        MaterialPageRoute<void>(builder: (_) => const SizedBox.shrink()),
      );
      await tester.pumpAndSettle();
      captureCompletion!.complete();
      await tester.pumpAndSettle();

      expect(initializationErrors, isEmpty);
      expect(controller, isNotNull);
      expect(tester.takeException(), isNull);

      calls.clear();
      await controller!.setZoom(0.5);
      await controller!.toggleFlash();
      await controller!.setCropArea(const CropRect(scaleWidth: 0.5));
      await controller!.startScan(250);

      expect(
        calls.map((call) => call.method),
        containsAll(<String>[
          'setZoom',
          'toggleFlash',
          'setCropAreaMethod',
          'startScan',
        ]),
      );
      expect(
        calls.where((call) => call.method == 'captureCamera'),
        isEmpty,
      );
    });

    testWidgets('A B A waits for initialization and publishes controller once',
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
      preview.onCameraInitialized(17);
      await tester.pump();

      final navigator = tester.state<NavigatorState>(find.byType(Navigator));
      navigator.push<void>(
        MaterialPageRoute<void>(builder: (_) => const SizedBox.shrink()),
      );
      await tester.pumpAndSettle();
      navigator.pop();
      await tester.pumpAndSettle();

      expect(
        calls.where((call) => call.method == 'captureCamera'),
        hasLength(2),
      );
      expect(controller, isNull);

      captureCompletion!.complete();
      await tester.pumpAndSettle();

      expect(initializationErrors, isEmpty);
      expect(initializationCount, 1);
      expect(controller, isNotNull);
      expect(tester.takeException(), isNull);
    });

    testWidgets('initialization error is reported after route is covered',
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

      preview.onCameraInitialized(17);
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
      captureCompletion!.completeError(error);
      await tester.pumpAndSettle();

      expect(initializationErrors, hasLength(1));
      final reportedError = initializationErrors.single;
      expect(reportedError, isA<CameraControlException>());
      expect(
        (reportedError as CameraControlException).operation,
        CameraControlOperation.torch,
      );
      expect(reportedError.viewId, 17);
      expect(controller, isNull);
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
