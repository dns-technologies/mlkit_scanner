import 'package:mlkit_scanner/mlkit_scanner.dart';

/// Represents a single recognized barcode and its value.
class Barcode {
  /// Barcode value as it was encoded in the barcode.
  final String rawValue;

  /// Barcode value in a user-friendly format.
  final String? displayValue;

  /// Barcode format.
  final BarcodeFormat format;

  /// Format type of the barcode value.
  final BarcodeValueType valueType;

  /// Creates a decoded barcode returned by the native recognizer.
  const Barcode({
    required this.rawValue,
    required this.valueType,
    required this.format,
    this.displayValue,
  });

  /// Creates a barcode from its platform-channel representation.
  factory Barcode.fromJson(Map<String, dynamic> json) {
    return Barcode(
      rawValue: json['raw_value'],
      displayValue: json['display_value'],
      valueType: BarcodeValueTypeCode.fromCode(json['value_type']),
      format: BarcodeFormatCode.fromCode(json['format']),
    );
  }
}
