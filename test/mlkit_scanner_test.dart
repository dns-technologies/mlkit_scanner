import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';
import 'package:mlkit_scanner/widgets/camera_preview.dart';

void main() {
  group('Тестирование MLKitscanner', () {
    const channel = MethodChannel('mlkit_channel');

    setUpAll(() {
      TestWidgetsFlutterBinding.ensureInitialized();

      channel.setMockMethodCallHandler((call) async {
        switch (call.method) {
          default:
            null;
        }
      });
    });

    tearDownAll(() {
      channel.setMockMethodCallHandler(null);
    });

    group('Тестрирование CameraPreview', () {
      testWidgets('Инициализация камеры', (tester) async {
        var cameraInitialized = false;
        await tester.pumpWidget(
          TestApp(
            child: CameraPreview(
              onCameraInitialized: () => cameraInitialized = true,
            ),
          ),
        );
        final platformView = find.byType(AndroidView);
        expect(platformView, findsOneWidget);
        final widget = tester.firstWidget(platformView) as AndroidView;
        widget.onPlatformViewCreated!(1);
        await tester.pumpAndSettle();
        expect(cameraInitialized, true,
            reason: 'Камера не проинициализировалась');
      });
    });

    group('Тестирование BarcodeScanner', () {
      testWidgets('Инициализация виджета', (tester) async {
        BarcodeScannerController? controller;
        await tester.pumpWidget(TestApp(
          child: BarcodeScanner(
            onScannerInitialized: (c) => controller = c,
            onScan: (value) {},
          ),
        ));
        final camera = find.byType(CameraPreview);
        expect(camera, findsOneWidget, reason: 'Нет виджета CameraPreview');
        final widget = tester.firstWidget(camera) as CameraPreview;
        widget.onCameraInitialized();
        await tester.pumpAndSettle();
        expect(controller, isNotNull,
            reason:
                'Виджет не вернул контроллер для управлением сканированием');
      });
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
