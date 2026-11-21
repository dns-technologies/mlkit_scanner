import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/barcode_format.dart';

void main() {
  group('$BarcodeFormat', () {
    test('platform codes keep their published values in both directions', () {
      const codes = {
        BarcodeFormat.unknown: 0,
        BarcodeFormat.code128: 1,
        BarcodeFormat.code39: 2,
        BarcodeFormat.code93: 4,
        BarcodeFormat.codabar: 8,
        BarcodeFormat.dataMatrix: 16,
        BarcodeFormat.ean13: 32,
        BarcodeFormat.ean8: 64,
        BarcodeFormat.itf: 128,
        BarcodeFormat.qrCode: 256,
        BarcodeFormat.upcA: 512,
        BarcodeFormat.upcE: 1024,
        BarcodeFormat.pdf417: 2048,
        BarcodeFormat.aztec: 4096,
      };

      expect(codes.keys, unorderedEquals(BarcodeFormat.values));
      for (final entry in codes.entries) {
        expect(entry.key.code, entry.value);
        expect(BarcodeFormatCode.fromCode(entry.value), entry.key);
      }
    });

    test('unknown platform code maps to unknown format', () {
      expect(BarcodeFormatCode.fromCode(-1), BarcodeFormat.unknown);
    });
  });
}
