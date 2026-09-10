import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/crop_rect.dart';

void main() {
  group('$CropRect', () {
    test('defaults describe the complete centered preview', () {
      expect(const CropRect().toJson(), {
        'scaleHeight': 1.0,
        'scaleWidth': 1.0,
        'offsetX': 0.0,
        'offsetY': 0.0,
      });
    });

    test('toJson preserves configured normalized geometry', () {
      expect(
        const CropRect(
          scaleWidth: 0.5,
          scaleHeight: 0.75,
          offsetX: -0.25,
          offsetY: 0.4,
        ).toJson(),
        {
          'scaleHeight': 0.75,
          'scaleWidth': 0.5,
          'offsetX': -0.25,
          'offsetY': 0.4,
        },
      );
    });
  });
}
