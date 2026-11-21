import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/ios_camera_position.dart';

void main() {
  group('$IosCameraPosition', () {
    test('platform codes keep their published values in both directions', () {
      const codes = {IosCameraPosition.unspecified: 0, IosCameraPosition.back: 1, IosCameraPosition.front: 2};

      expect(codes.keys, unorderedEquals(IosCameraPosition.values));
      for (final entry in codes.entries) {
        expect(entry.key.code, entry.value);
        expect(IosCameraPositionCode.fromCode(entry.value), entry.key);
      }
    });

    test('unknown platform code is rejected', () {
      expect(() => IosCameraPositionCode.fromCode(-1), throwsA(isA<Error>()));
    });
  });
}
