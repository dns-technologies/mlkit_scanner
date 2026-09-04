import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/ios_camera_type.dart';

void main() {
  group('$IosCameraType', () {
    test('every iOS camera type round-trips through its platform code', () {
      for (final type in IosCameraType.values) {
        expect(IosCameraTypeCode.fromCode(type.code), type);
      }
    });

    test('unknown platform code is rejected', () {
      expect(() => IosCameraTypeCode.fromCode(-1), throwsA(isA<Error>()));
    });
  });
}
