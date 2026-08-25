import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/widgets/camera_preview.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('$CameraPreview', () {
    const channel = MethodChannel('mlkit_channel');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

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
      messenger.setMockMethodCallHandler(channel, (call) async => null);

      messenger.setMockMethodCallHandler(
        SystemChannels.platform_views,
        (call) async {
          switch (call.method) {
            default:
              return null;
          }
        },
      );
    });

    tearDown(() {
      messenger.setMockMethodCallHandler(channel, null);
      messenger.setMockMethodCallHandler(SystemChannels.platform_views, null);
    });

    group('Инициализация виджета при успешной инициализации камеры', () {
      testWidgets('Android', (tester) async {
        debugDefaultTargetPlatformOverride = TargetPlatform.android;
        var cameraInitialized = false;
        MethodCall? initCall;
        MethodCall? disposeCall;
        PlatformException? error;
        messenger.setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'initCameraPreview') initCall = call;
          if (call.method == 'dispose') disposeCall = call;
          return null;
        });

        await tester.pumpWidget(_buildApp(
          onCameraInitialized: () => cameraInitialized = true,
          onCameraInitializeError: (e) => error = e,
        ));

        final platformView = find.byType(PlatformViewLink);
        await tester.pumpAndSettle();
        expect(platformView, findsOneWidget,
            reason: "Не отображается нативный виджет");
        expect(cameraInitialized, true,
            reason: 'Не вызвался колбек при успешной инициализации камеры');
        expect(error, isNull,
            reason: "Не должно быть ошибки инициализации камеры");
        expect(initCall?.arguments, isNull);
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
        expect(disposeCall?.arguments, {'viewId': isA<int>()});
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
        expect(platformView, findsOneWidget,
            reason: "Не отображается нативный виджет");
        expect(cameraInitialized, true,
            reason: 'Не вызвался колбек при успешной инициализации камеры');
        expect(error, isNull,
            reason: "Не должно быть ошибки инициализации камеры");
        debugDefaultTargetPlatformOverride = null;
      });
    });

    testWidgets('Инициализация виджета при ошибке инициализации камеры',
        (tester) async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'initCameraPreview') {
          throw PlatformException(code: "911", message: "Ошибочка");
        }
        return null;
      });
      var cameraInitialized = false;
      late PlatformException error;

      await tester.pumpWidget(_buildApp(
        onCameraInitialized: () => cameraInitialized = true,
        onCameraInitializeError: (e) => error = e,
      ));

      final platformView = find.byType(PlatformViewLink);
      await tester.pumpAndSettle();
      expect(platformView, findsOneWidget,
          reason: "Не отображается нативный виджет");
      expect(cameraInitialized, false,
          reason: 'Колбек инициализации не должен вызываться при ошибке');
      expect(error.message, "Ошибочка",
          reason: "Должна вернуться ошибка инициализации камеры");
    });
  });
}
