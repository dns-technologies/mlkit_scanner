import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/ios_camera_position.dart';

void main() {
  group('$IosCameraPosition', () {
    test('every iOS camera position round-trips through its platform code', () {
      for (final position in IosCameraPosition.values) {
        expect(IosCameraPositionCode.fromCode(position.code), position);
      }
    });

    test('unknown platform code is rejected', () {
      expect(() => IosCameraPositionCode.fromCode(-1), throwsA(isA<Error>()));
    });
  });
}
