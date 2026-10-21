import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';
import 'package:mlkit_scanner/platform/ml_kit_channel.dart';

import '../support/runtime_harness.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  group('$MlKitChannel', () {
    late RuntimeHarness h;
    setUp(() => h = RuntimeHarness());
    tearDown(() => h.dispose());

    group('camera failures', () {
      test('decodes a native camera message and cause without an operation tag', () async {
        h.handler =
            (call) async =>
                throw PlatformException(
                  code: '9',
                  message: 'Failed to set camera zoom ratio to 2.0',
                  details: const {
                    'viewId': 42,
                    'cause': {'type': 'NativeError', 'message': 'Camera closed', 'stackTrace': 'native trace'},
                  },
                );

        await expectLater(
          MlKitChannel().updateCameraSettings(captureId: 'capture', zoomRatio: 2),
          throwsA(
            isA<CameraException>()
                .having((error) => error.message, 'message', 'Failed to set camera zoom ratio to 2.0')
                .having((error) => error.viewId, 'viewId', 42)
                .having((error) => error.cause?.stackTrace, 'native stack', 'native trace'),
          ),
        );
      });

      test('keeps other platform error categories unchanged', () async {
        h.handler = (call) async => throw PlatformException(code: '4', message: 'Device has no flash');

        await expectLater(
          MlKitChannel().updateCameraSettings(captureId: 'capture', torchEnabled: true),
          throwsA(
            isA<PlatformException>()
                .having((error) => error.code, 'code', '4')
                .having((error) => error is CameraException, 'camera error', false),
          ),
        );
      });
    });

    test('channel streams expose all view-tagged events before any capture', () async {
      final scanViews = <int>[];
      final torchViews = <int>[];
      expect(identical(MlKitChannel(), MlKitChannel()), isTrue);
      addTearDown(MlKitChannel().scanResults.listen((e) => scanViews.add(e.viewId)).cancel);
      addTearDown(MlKitChannel().torchToggleStream.listen((e) => torchViews.add(e.viewId)).cancel);
      await h.event(1, 'first');
      await h.event(2, 'second');
      expect(scanViews, [1, 2]);
      expect(torchViews, [1, 2]);
    });

    group('preview decoding', () {
      Map<String, Object> description(int id) => {
        'textureId': id,
        'width': 1280,
        'height': 720,
        'rotationDegrees': 90,
        'mirrored': false,
        'state': 'streaming',
      };

      test('invalid initial preview metadata releases its allocated endpoint', () async {
        h.handler = (call) async {
          if (call.method == 'subscribePreview') {
            return {'subscriptionId': 'invalid-preview', 'description': description(9)..['width'] = 0};
          }
          return null;
        };

        await expectLater(MlKitChannel().subscribePreview(), throwsFormatException);

        final cleanup = h.calls.where((call) => call.method == 'unsubscribePreview');
        expect(cleanup, hasLength(1));
        expect(cleanup.single.arguments, containsPair('subscriptionId', 'invalid-preview'));
      });
    });
  });
}
