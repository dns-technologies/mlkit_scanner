enum BarcodeValueType {
  unknown,
  contactInfo,
  email,
  isbn,
  phone,
  product,
  sms,
  text,
  url,
  wifi,
  geo,
  calendarEvent,
  driverLicense,
}

extension BarcodeValueTypeCode on BarcodeValueType {
  /// Code of type for transmission over the platform channel.
  int get code => _typeToCode[this]!;

  /// Returns the type corresponding to the [code].
  static BarcodeValueType fromCode(int code) => _codeToType[code]!;

  static final _typeToCode = {
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

  static final _codeToType = {
    for (final entry in _typeToCode.entries) entry.value: entry.key,
  };
}
