import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/crop_rect.dart';
import 'package:mlkit_scanner/widgets/camera_preview.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('$CameraPreview', () {
    const channel = MethodChannel('mlkit_channel');
    const messageCodec = StandardMessageCodec();
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

    Widget buildApp({
      required ValueChanged<int> onCameraInitialized,
      double? initialZoom,
      bool? initialFlashEnabled,
      CropRect? initialCropRect,
    }) {
      return MaterialApp(
        home: CameraPreview(
          onCameraInitialized: onCameraInitialized,
          initialZoom: initialZoom,
          initialFlashEnabled: initialFlashEnabled,
          initialCropRect: initialCropRect,
        ),
      );
    }

    setUp(() {
      messenger.setMockMethodCallHandler(channel, (call) async => null);
      messenger.setMockMethodCallHandler(
        SystemChannels.platform_views,
        (call) async => null,
      );
    });

    tearDown(() {
      debugDefaultTargetPlatformOverride = null;
      messenger.setMockMethodCallHandler(channel, null);
      messenger.setMockMethodCallHandler(SystemChannels.platform_views, null);
    });

    testWidgets('Android registers configured native view without channel init',
        (tester) async {
      debugDefaultTargetPlatformOverride = TargetPlatform.android;
      final channelCalls = <MethodCall>[];
      MethodCall? platformCreateCall;
      int? initializedViewId;
      messenger.setMockMethodCallHandler(channel, (call) async {
        channelCalls.add(call);
        return null;
      });
      messenger.setMockMethodCallHandler(
        SystemChannels.platform_views,
        (call) async {
          if (call.method == 'create') platformCreateCall = call;
          return null;
        },
      );

      await tester.pumpWidget(buildApp(
        onCameraInitialized: (viewId) => initializedViewId = viewId,
        initialZoom: 0.4,
        initialFlashEnabled: false,
        initialCropRect: const CropRect(
          scaleWidth: 0.5,
          scaleHeight: 0.75,
          offsetX: 0.1,
          offsetY: -0.1,
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.byType(PlatformViewLink), findsOneWidget);
      final surface = tester.widget<AndroidViewSurface>(
        find.byType(AndroidViewSurface),
      );
      expect(
        surface.gestureRecognizers.map((factory) => factory.type).toSet(),
        {TapGestureRecognizer, LongPressGestureRecognizer},
      );
      expect(initializedViewId, isNotNull);
      expect(
        channelCalls.map((call) => call.method),
        isNot(contains('initCameraPreview')),
      );

      final createArguments =
          Map<Object?, Object?>.from(platformCreateCall!.arguments as Map);
      final encodedParams = createArguments['params']! as Uint8List;
      final creationParams = Map<Object?, Object?>.from(
        messageCodec.decodeMessage(ByteData.sublistView(encodedParams)) as Map,
      );
      expect(creationParams, {
        'viewId': initializedViewId,
        'width': 800.0,
        'height': 600.0,
        'initialZoom': 0.4,
        'initialFlashEnabled': false,
        'initialCropRect': {
          'scaleWidth': 0.5,
          'scaleHeight': 0.75,
          'offsetX': 0.1,
          'offsetY': -0.1,
        },
      });

      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      expect(
        channelCalls.map((call) => call.method),
        isNot(contains('dispose')),
      );
      debugDefaultTargetPlatformOverride = null;
    });
  });
}
