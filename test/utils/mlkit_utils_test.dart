import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/ios_camera_position.dart';
import 'package:mlkit_scanner/models/ios_camera_type.dart';
import 'package:mlkit_scanner/utils/mlkit_utils.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('mlkit_channel');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  group('$MLKitUtils', () {
    tearDown(() => messenger.setMockMethodCallHandler(channel, null));

    test('getIosAvailableCameras delegates to the channel and decodes values',
        () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return <Object?>[
          <String, Object?>{'type': 0, 'position': 1},
          <String, Object?>{'type': 3, 'position': 2},
        ];
      });

      final cameras = await MLKitUtils().getIosAvailableCameras();

      expect(calls.map((call) => call.method), ['getIosAvailableCameras']);
      expect(cameras, hasLength(2));
      expect(cameras.first.type, IosCameraType.builtInWideAngleCamera);
      expect(cameras.first.position, IosCameraPosition.back);
      expect(cameras.last.type, IosCameraType.builtInUltraWideCamera);
      expect(cameras.last.position, IosCameraPosition.front);
    });
  });
}
