import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/ios_camera_type.dart';

void main() {
  group('$IosCameraType', () {
    test('platform codes keep their published values in both directions', () {
      const codes = {
        IosCameraType.builtInWideAngleCamera: 0,
        IosCameraType.builtInTelephotoCamera: 1,
        IosCameraType.builtInDualCamera: 2,
        IosCameraType.builtInUltraWideCamera: 3,
        IosCameraType.builtInDualWideCamera: 4,
        IosCameraType.builtInTripleCamera: 5,
      };

      expect(codes.keys, unorderedEquals(IosCameraType.values));
      for (final entry in codes.entries) {
        expect(entry.key.code, entry.value);
        expect(IosCameraTypeCode.fromCode(entry.value), entry.key);
      }
    });

    test('unknown platform code is rejected', () {
      expect(() => IosCameraTypeCode.fromCode(-1), throwsA(isA<Error>()));
    });
  });
}
