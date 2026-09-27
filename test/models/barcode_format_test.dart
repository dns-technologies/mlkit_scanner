import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/barcode_format.dart';

void main() {
  group('$BarcodeFormat', () {
    test('every barcode format round-trips through its platform code', () {
      for (final format in BarcodeFormat.values) {
        expect(BarcodeFormatCode.fromCode(format.code), format);
      }
    });

    test('unknown platform code maps to unknown format', () {
      expect(BarcodeFormatCode.fromCode(-1), BarcodeFormat.unknown);
    });
  });
}
