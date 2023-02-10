import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/widgets/camera_preview.dart';

void main() {
  group('$CameraPreview', () {
    const channel = MethodChannel('mlkit_channel');

    Widget _buildApp({
      required Function() onCameraInitialized,
      Function(PlatformException)? onCameraInitializeError,
    }) {
      return MaterialApp(
        home: CameraPreview(
          onCameraInitialized: onCameraInitialized,
          onCameraInitializeError: onCameraInitializeError,
        ),
      );
    }

    setUp(() {
      channel.setMockMethodCallHandler((call) async => null);

      SystemChannels.platform_views.setMockMethodCallHandler(
        (call) async {
          switch (call.method) {
            default:
              null;
          }
        },
      );
    });

    tearDown(() {
      channel.setMockMethodCallHandler(null);
      SystemChannels.platform_views.setMockMethodCallHandler(null);
    });

    group('Инициализация виджета при успешной инициализации камеры', () {
      testWidgets('Android', (tester) async {
        debugDefaultTargetPlatformOverride = TargetPlatform.android;
        var cameraInitialized = false;
        PlatformException? error;

        await tester.pumpWidget(_buildApp(
          onCameraInitialized: () => cameraInitialized = true,
          onCameraInitializeError: (e) => error = e,
        ));

        final platformView = find.byType(PlatformViewLink);
        await tester.pumpAndSettle();
        expect(platformView, findsOneWidget, reason: "Не отображается нативный виджет");
        expect(cameraInitialized, true, reason: 'Не вызвался колбек при успешной инициализации камеры');
        expect(error, isNull, reason: "Не должно быть ошибки инициализации камеры");
        debugDefaultTargetPlatformOverride = null;
      });

      testWidgets('IOS', (tester) async {
        debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
        var cameraInitialized = false;
        PlatformException? error;

        await tester.pumpWidget(_buildApp(
          onCameraInitialized: () => cameraInitialized = true,
          onCameraInitializeError: (e) => error = e,
        ));

        final platformView = find.byType(UiKitView);
        final widget = tester.firstWidget(platformView) as UiKitView;

        widget.onPlatformViewCreated!(1);
        await tester.pumpAndSettle();
        expect(platformView, findsOneWidget, reason: "Не отображается нативный виджет");
        expect(cameraInitialized, true, reason: 'Не вызвался колбек при успешной инициализации камеры');
        expect(error, isNull, reason: "Не должно быть ошибки инициализации камеры");
        debugDefaultTargetPlatformOverride = null;
      });
    });

    testWidgets('Инициализация виджета при ошибке инициализации камеры', (tester) async {
      channel.setMockMethodCallHandler((call) async {
        if (call.method == 'initCameraPreview') {
          throw PlatformException(code: "911", message: "Ошибочка");
        }
      });
      var cameraInitialized = false;
      late PlatformException error;

      await tester.pumpWidget(_buildApp(
        onCameraInitialized: () => cameraInitialized = true,
        onCameraInitializeError: (e) => error = e,
      ));

      final platformView = find.byType(PlatformViewLink);
      await tester.pumpAndSettle();
      expect(platformView, findsOneWidget, reason: "Не отображается нативный виджет");
      expect(cameraInitialized, false, reason: 'Колбек инициализации не должен вызываться при ошибке');
      expect(error.message, "Ошибочка", reason: "Должна вернуться ошибка инициализации камеры");
    });
  });
}
