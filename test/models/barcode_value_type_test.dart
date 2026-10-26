import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/barcode_value_type.dart';

void main() {
  group('$BarcodeValueType', () {
    test('platform codes keep their published values in both directions', () {
      const codes = {
        BarcodeValueType.unknown: 0,
        BarcodeValueType.contactInfo: 1,
        BarcodeValueType.email: 2,
        BarcodeValueType.isbn: 3,
        BarcodeValueType.phone: 4,
        BarcodeValueType.product: 5,
        BarcodeValueType.sms: 6,
        BarcodeValueType.text: 7,
        BarcodeValueType.url: 8,
        BarcodeValueType.wifi: 9,
        BarcodeValueType.geo: 10,
        BarcodeValueType.calendarEvent: 11,
        BarcodeValueType.driverLicense: 12,
      };

      expect(codes.keys, unorderedEquals(BarcodeValueType.values));
      for (final entry in codes.entries) {
        expect(entry.key.code, entry.value);
        expect(BarcodeValueTypeCode.fromCode(entry.value), entry.key);
      }
    });

    test('unknown platform code maps to unknown value type', () {
      expect(BarcodeValueTypeCode.fromCode(-1), BarcodeValueType.unknown);
    });
  });
}
