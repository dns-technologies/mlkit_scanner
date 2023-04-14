import 'package:mlkit_scanner/mlkit_scanner.dart';

class Barcode {
  final String rawValue;

  final String? displayValue;

  final BarcodeFormat format;

  final BarcodeValueType valueType;

  const Barcode({
    required this.rawValue,
    required this.valueType,
    required this.format,
    this.displayValue,
  });

  factory Barcode.fromJson(Map<String, dynamic> json) {
    return Barcode(
      rawValue: json['raw_value'],
      displayValue: json['display_value'],
      valueType: BarcodeValueTypeCode.fromCode(json['value_type']),
      format: BarcodeFormatCode.fromCode(json['format']),
    );
  }
}
