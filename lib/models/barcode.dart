import 'barcode_format.dart';
import 'barcode_value_type.dart';

/// Represents a single recognized barcode and its value.
class Barcode {
  const Barcode({required this.rawValue, required this.valueType, required this.format, this.displayValue});

  factory Barcode.fromJson(Map<String, dynamic> json) {
    return Barcode(
      rawValue: json['raw_value'],
      displayValue: json['display_value'],
      valueType: BarcodeValueTypeCode.fromCode(json['value_type']),
      format: BarcodeFormatCode.fromCode(json['format']),
    );
  }

  /// Barcode value as it was encoded in the barcode.
  final String rawValue;

  /// Barcode value in a user-friendly format.
  final String? displayValue;

  /// Encoding symbology, independent of the decoded content type.
  final BarcodeFormat format;

  /// Semantic classification of the decoded contents.
  final BarcodeValueType valueType;
}
