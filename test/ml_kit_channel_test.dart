import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
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

  test('camera and scanner commands use the shared session contract', () async {
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
    );

    expect(calls[0].arguments, 0.5);
    expect(calls[1].arguments, {
      'type': RecognitionType.barcodeRecognition.rawValue,
      'delay': 200,
    });
  });

  test('scan events are delivered to every open subscription', () async {
    final channel = MlKitChannel();
    final firstResults = <String>[];
    final secondResults = <String>[];
    final firstStream = await channel.startScan(
      RecognitionType.barcodeRecognition,
      0,
    );
    final secondStream = await channel.startScan(
      RecognitionType.barcodeRecognition,
      0,
    );
    final firstSubscription =
        firstStream.listen((barcode) => firstResults.add(barcode.rawValue));
    final secondSubscription =
        secondStream.listen((barcode) => secondResults.add(barcode.rawValue));

    await sendNativeCall(
      codec,
      const MethodCall('onScanResult', {
        'raw_value': 'shared',
        'display_value': 'shared',
        'format': 1,
        'value_type': 7,
      }),
    );
    await Future<void>.delayed(Duration.zero);

    expect(firstResults, ['shared']);
    expect(secondResults, ['shared']);
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
