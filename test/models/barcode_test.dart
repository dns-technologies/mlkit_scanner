import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/barcode.dart';
import 'package:mlkit_scanner/models/barcode_format.dart';
import 'package:mlkit_scanner/models/barcode_value_type.dart';

void main() {
  group('$Barcode', () {
    test('fromJson decodes the complete platform payload', () {
      final barcode = Barcode.fromJson(const {
        'raw_value': 'raw',
        'display_value': 'display',
        'format': 256,
        'value_type': 8,
      });

      expect(barcode.rawValue, 'raw');
      expect(barcode.displayValue, 'display');
      expect(barcode.format, BarcodeFormat.qrCode);
      expect(barcode.valueType, BarcodeValueType.url);
    });

    test('fromJson keeps a missing display value nullable', () {
      final barcode = Barcode.fromJson(const {
        'raw_value': 'raw',
        'display_value': null,
        'format': -1,
        'value_type': -1,
      });

      expect(barcode.displayValue, isNull);
      expect(barcode.format, BarcodeFormat.unknown);
      expect(barcode.valueType, BarcodeValueType.unknown);
    });
  });
}
