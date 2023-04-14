enum BarcodeFormat {
  unknown,
  allFormats,
  code128,
  code39,
  code93,
  codabar,
  dataMatrix,
  ean13,
  ean8,
  itf,
  qrCode,
  upcA,
  upcE,
  pdf417,
  aztec,
}

extension BarcodeFormatCode on BarcodeFormat {
  /// Code of format for transmission over the platform channel.
  int get code => _formatToCode[this]!;

  /// Returns the format corresponding to the [code].
  static BarcodeFormat fromCode(int code) => _codeToFormat[code]!;

  static final _formatToCode = {
    BarcodeFormat.unknown: 0,
    BarcodeFormat.allFormats: 0xffff,
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
