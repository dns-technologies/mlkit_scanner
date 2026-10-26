import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/crop_rect.dart';

void main() {
  group('$CropRect', () {
    test('validity permits finite rectangles extending beyond the preview', () {
      expect(const CropRect().isValid, isTrue);
      expect(const CropRect(scaleWidth: 2, scaleHeight: .5, offsetX: -3, offsetY: 4).isValid, isTrue);
    });

    test('validity rejects nonpositive scales and nonfinite coordinates', () {
      for (final value in [0.0, -1.0, double.nan, double.infinity, double.negativeInfinity]) {
        expect(CropRect(scaleWidth: value).isValid, isFalse, reason: 'width: $value');
        expect(CropRect(scaleHeight: value).isValid, isFalse, reason: 'height: $value');
      }
      for (final value in [double.nan, double.infinity, double.negativeInfinity]) {
        expect(CropRect(offsetX: value).isValid, isFalse, reason: 'offsetX: $value');
        expect(CropRect(offsetY: value).isValid, isFalse, reason: 'offsetY: $value');
      }
    });

    test('defaults describe the complete centered preview', () {
      expect(const CropRect().toJson(), {'scaleHeight': 1.0, 'scaleWidth': 1.0, 'offsetX': 0.0, 'offsetY': 0.0});
    });

    test('toJson preserves configured normalized geometry', () {
      expect(const CropRect(scaleWidth: 0.5, scaleHeight: 0.75, offsetX: -0.25, offsetY: 0.4).toJson(), {
        'scaleHeight': 0.75,
        'scaleWidth': 0.5,
        'offsetX': -0.25,
        'offsetY': 0.4,
      });
    });
  });
}
