import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/widgets/camera_preview.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('$CameraPreview', () {
    const channel = MethodChannel('mlkit_channel');
    const messageCodec = StandardMessageCodec();
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

    Widget buildApp({
      required ValueChanged<int> onCameraInitialized,
    }) {
      return MaterialApp(
        home: CameraPreview(
          onCameraInitialized: onCameraInitialized,
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

    testWidgets('Android registers only its UI address without initializing hardware', (tester) async {
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
      expect(channelCalls, isEmpty);

      final createArguments = Map<Object?, Object?>.from(platformCreateCall!.arguments as Map);
      final encodedParams = createArguments['params']! as Uint8List;
      final creationParams = Map<Object?, Object?>.from(
        messageCodec.decodeMessage(ByteData.sublistView(encodedParams)) as Map,
      );
      expect(creationParams, {'viewId': initializedViewId});

      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      expect(channelCalls, isEmpty);
      debugDefaultTargetPlatformOverride = null;
    });

    testWidgets('iOS creates only a UI container with no retained settings', (tester) async {
      debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
      final channelCalls = <MethodCall>[];
      MethodCall? platformCreateCall;
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
        onCameraInitialized: (_) {},
      ));
      await tester.pumpAndSettle();

      expect(find.byType(UiKitView), findsOneWidget);
      expect(channelCalls, isEmpty);
      final createArguments = Map<Object?, Object?>.from(platformCreateCall!.arguments as Map);
      expect(createArguments['params'], isNull);
      debugDefaultTargetPlatformOverride = null;
    });
  });
}
