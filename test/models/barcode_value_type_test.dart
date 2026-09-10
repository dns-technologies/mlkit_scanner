import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/barcode_value_type.dart';

void main() {
  group('$BarcodeValueType', () {
    test('every barcode value type round-trips through its platform code', () {
      for (final type in BarcodeValueType.values) {
        expect(BarcodeValueTypeCode.fromCode(type.code), type);
      }
    });

    test('unknown platform code maps to unknown value type', () {
      expect(BarcodeValueTypeCode.fromCode(-1), BarcodeValueType.unknown);
    });
  });
}
