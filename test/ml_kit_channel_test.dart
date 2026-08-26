import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/crop_rect.dart';
import 'package:mlkit_scanner/models/ios_camera.dart';
import 'package:mlkit_scanner/models/ios_camera_position.dart';
import 'package:mlkit_scanner/models/ios_camera_type.dart';
import 'package:mlkit_scanner/models/recognition_type.dart';
import 'package:mlkit_scanner/platform/ml_kit_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const methodChannel = MethodChannel('mlkit_channel');
  const codec = StandardMethodCodec();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    messenger.setMockMethodCallHandler(methodChannel, (call) async => null);
  });

  tearDown(() {
    debugDefaultTargetPlatformOverride = null;
    messenger.setMockMethodCallHandler(methodChannel, null);
  });

  test('camera and scanner lifecycle commands include their viewId', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(methodChannel, (call) async {
      calls.add(call);
      return null;
    });
    final channel = MlKitChannel();

    await channel.setZoom(0.5);
    await channel.startScan(
      RecognitionType.barcodeRecognition,
      200,
      viewId: 42,
    );
    await channel.cancelScan(viewId: 42);
    await channel.pauseCamera(viewId: 42);
    await channel.resumeCamera(viewId: 42);

    expect(calls[0].arguments, 0.5);
    expect(calls[1].arguments, {
      'viewId': 42,
      'type': RecognitionType.barcodeRecognition.rawValue,
      'delay': 200,
    });
    expect(calls[2].arguments, {'viewId': 42});
    expect(calls[3].arguments, {'viewId': 42});
    expect(calls[4].arguments, {'viewId': 42});
  });

  test('camera initialization sends initial settings directly', () async {
    MethodCall? initCall;
    messenger.setMockMethodCallHandler(methodChannel, (call) async {
      if (call.method == 'initCameraPreview') initCall = call;
      return null;
    });
    final channel = MlKitChannel();

    await channel.initCameraPreview(
      viewId: 42,
      initialZoom: 0.4,
      initialCropRect: const CropRect(
        scaleWidth: 0.5,
        scaleHeight: 0.75,
        offsetX: 0.1,
        offsetY: -0.1,
      ),
      initialCamera: const IosCamera(
        position: IosCameraPosition.back,
        type: IosCameraType.builtInWideAngleCamera,
      ),
    );

    expect(initCall?.arguments, {
      'viewId': 42,
      'initialZoom': 0.4,
      'initialCropRect': {
        'scaleWidth': 0.5,
        'scaleHeight': 0.75,
        'offsetX': 0.1,
        'offsetY': -0.1,
      },
      'initialCamera': {
        'position': 1,
        'type': 0,
      },
    });
  });

  test('scan events are delivered only to the matching view', () async {
    final channel = MlKitChannel();
    final firstResults = <String>[];
    final secondResults = <String>[];
    final firstStream = channel.scanResults(11);
    final secondStream = channel.scanResults(22);
    final firstSubscription =
        firstStream.listen((barcode) => firstResults.add(barcode.rawValue));
    final secondSubscription =
        secondStream.listen((barcode) => secondResults.add(barcode.rawValue));

    await sendNativeCall(
      codec,
      const MethodCall('onScanResult', {
        'viewId': 11,
        'barcode': {
          'raw_value': 'first',
          'display_value': 'first',
          'format': 1,
          'value_type': 7,
        },
      }),
    );
    await sendNativeCall(
      codec,
      const MethodCall('onScanResult', {
        'viewId': 22,
        'barcode': {
          'raw_value': 'second',
          'display_value': 'second',
          'format': 1,
          'value_type': 7,
        },
      }),
    );
    await Future<void>.delayed(Duration.zero);

    expect(firstResults, ['first']);
    expect(secondResults, ['second']);
    await firstSubscription.cancel();
    await secondSubscription.cancel();
  });

  test('legacy scan payload without viewId is ignored', () async {
    final channel = MlKitChannel();
    final results = <String>[];
    final stream = channel.scanResults(11);
    final subscription = stream.listen(
      (barcode) => results.add(barcode.rawValue),
    );

    await sendNativeCall(
      codec,
      const MethodCall('onScanResult', {
        'raw_value': 'legacy',
        'display_value': 'legacy',
        'format': 1,
        'value_type': 7,
      }),
    );
    await Future<void>.delayed(Duration.zero);

    expect(results, isEmpty);
    await subscription.cancel();
  });

  test('torch events are filtered by viewId', () async {
    final channel = MlKitChannel();
    final firstValues = <bool>[];
    final secondValues = <bool>[];
    final firstSubscription =
        channel.torchToggleStream(11).listen(firstValues.add);
    final secondSubscription =
        channel.torchToggleStream(22).listen(secondValues.add);

    await sendNativeCall(
      codec,
      const MethodCall('changeTorchStateMethod', {
        'viewId': 22,
        'value': true,
      }),
    );
    await Future<void>.delayed(Duration.zero);

    expect(firstValues, isEmpty);
    expect(secondValues, [true]);
    await firstSubscription.cancel();
    await secondSubscription.cancel();
  });
}

Future<void> sendNativeCall(
  MethodCodec codec,
  MethodCall call,
) async {
  final completed = Completer<void>();
  final ByteData data = codec.encodeMethodCall(call);
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .handlePlatformMessage(
    'mlkit_channel',
    data,
    (_) => completed.complete(),
  );
  await completed.future;
}
