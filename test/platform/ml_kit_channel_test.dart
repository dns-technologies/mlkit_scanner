import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';
import 'package:mlkit_scanner/platform/ml_kit_channel.dart';
import 'package:mlkit_scanner/platform/scanner_configuration.dart';

import '../support/runtime_harness.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  group('$MlKitChannel', () {
    late RuntimeHarness h;
    setUp(() => h = RuntimeHarness());
    tearDown(() => h.dispose());

    group('camera failures', () {
      test('exposes the native message and stack trace directly on the Dart exception', () async {
        h.handler =
            (call) async =>
                throw PlatformException(
                  code: '9',
                  message: 'Failed to set camera zoom ratio to 2.0: Camera closed',
                  details: const {'stacktrace': 'native trace'},
                );

        await expectLater(
          MlKitChannel().updateCameraSettings(captureId: 'capture', zoomRatio: 2),
          throwsA(
            isA<CameraException>()
                .having((error) => error.message, 'message', 'Failed to set camera zoom ratio to 2.0: Camera closed')
                .having((error) => error.details, 'details', isNull)
                .having((error) => error.stacktrace, 'native stack', 'native trace'),
          ),
        );
      });

      test('decodes other known native error categories', () async {
        h.handler = (call) async => throw PlatformException(code: '4', message: 'Device has no flash');

        await expectLater(
          MlKitChannel().updateCameraSettings(captureId: 'capture', torchEnabled: true),
          throwsA(
            isA<FlashUnavailableException>()
                .having((error) => error.code, 'code', '4')
                .having((error) => error is CameraException, 'camera error', false),
          ),
        );
      });
    });

    final channel = MlKitChannel();
    final commands = <String, Future<void> Function()>{
      'stopCamera': () => channel.stopCamera(captureId: 'capture'),
      'resumeCamera':
          () => channel.resumeCamera(configuration: const ScannerConfiguration(), captureId: 'capture', geometry: const Size(100, 100)),
      'updateCameraSettings': () => channel.updateCameraSettings(captureId: 'capture', torchEnabled: true),
      'setScanDelay': () => channel.setScanDelay(0, captureId: 'capture'),
      'startScan': () => channel.startScan(0, captureId: 'capture', subscriptionId: 'scan'),
      'cancelScan': () => channel.cancelScan(captureId: 'capture'),
      'registerScanner': () => channel.registerScanner(42),
      'unregisterScanner': () => channel.unregisterScanner(42),
      'openCapture': () => channel.openCapture(42),
      'closeCapture': () => channel.closeCapture('capture'),
      'disposeScanner': channel.disposeScanner,
      'subscribePreview': channel.subscribePreview,
      'unsubscribePreview': () => channel.unsubscribePreview('preview'),
      'subscribeScan': () => channel.subscribeScan('capture'),
      'updatePreviewGeometry': () => channel.updatePreviewGeometry('capture', const Size(100, 100)),
      'focus': () => channel.focus('capture', true),
      'getIosAvailableCameras': channel.getIosAvailableCameras,
    };

    for (final command in commands.entries) {
      test('${command.key} exposes typed permission failures', () async {
        h.handler = (_) async => throw PlatformException(code: '2', message: 'Permission denied');

        await expectLater(
          command.value(),
          throwsA(isA<CameraPermissionException>().having((error) => error.message, 'message', 'Permission denied')),
        );
      });

      test('${command.key} exposes unknown failures without losing their code', () async {
        h.handler = (_) async => throw PlatformException(code: 'new-code', message: 'Unexpected failure');

        await expectLater(command.value(), throwsA(isA<UnknownScannerException>().having((error) => error.code, 'code', 'new-code')));
      });
    }

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
