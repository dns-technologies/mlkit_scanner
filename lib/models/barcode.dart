class Barcode {
  final String rawValue;

  final String? displayValue;

  const Barcode({
    required this.rawValue,
    this.displayValue,
  });

  factory Barcode.fromJson(Map<String, dynamic> json) {
    return Barcode(
      rawValue: json['rawValue'],
      displayValue: json['displayValue'],
    );
  }
}
