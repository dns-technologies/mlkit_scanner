import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/ios_camera.dart';
import 'package:mlkit_scanner/models/ios_camera_position.dart';
import 'package:mlkit_scanner/models/ios_camera_type.dart';

void main() {
  group('$IosCamera', () {
    test('fromJson decodes camera type and position', () {
      final camera = IosCamera.fromJson(const {'type': 3, 'position': 2});

      expect(camera.type, IosCameraType.builtInUltraWideCamera);
      expect(camera.position, IosCameraPosition.front);
    });
  });
}
