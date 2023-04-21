/// Barcode format.
enum BarcodeFormat {
  /// Unknown format.
  unknown,

  /// Code 128.
  code128,

  /// Code 39.
  code39,

  /// Code 93.
  code93,

  /// Codabar.
  codabar,

  /// Data Matrix.
  dataMatrix,

  /// EAN-13.
  ean13,

  /// EAN-8.
  ean8,

  /// ITF (Interleaved Two-of-Five).
  itf,

  /// QR Code.
  qrCode,

  /// UPC-A.
  upcA,

  /// UPC-E.
  upcE,

  /// PDF-417.
  pdf417,

  /// AZTEC.
  aztec,
}

extension BarcodeFormatCode on BarcodeFormat {
  /// Code of format for transmission over the platform channel.
  int get code => _formatToCode[this]!;

  /// Returns the format corresponding to the [code].
  static BarcodeFormat fromCode(int code) =>
      _codeToFormat[code] ?? BarcodeFormat.unknown;

  static final _formatToCode = {
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

  static final _codeToFormat = {
    for (final entry in _formatToCode.entries) entry.value: entry.key,
  };
}
