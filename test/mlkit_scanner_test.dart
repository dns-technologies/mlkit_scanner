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
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

    setUpAll(() {
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return null;
      });
    });

    setUp(calls.clear);

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
