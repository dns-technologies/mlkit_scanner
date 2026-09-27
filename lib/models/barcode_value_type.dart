/// Barcode content value type.
enum BarcodeValueType {
  /// The native content type is absent or not recognized by this plugin version.
  unknown,

  /// Contact information.
  contactInfo,

  /// Email message details.
  email,

  /// ISBN.
  isbn,

  /// Phone number.
  phone,

  /// Product code.
  product,

  /// SMS details.
  sms,

  /// Plain text.
  text,

  /// URLs/bookmarks.
  url,

  /// WiFi access point details.
  wifi,

  /// Geographic coordinates.
  geo,

  /// Calendar event.
  calendarEvent,

  /// Driver's license data.
  driverLicense,
}

/// Converts between [BarcodeValueType] values and native platform codes.
extension BarcodeValueTypeCode on BarcodeValueType {
  /// Platform code for an unrecognized barcode content type.
  static const _unknownCode = 0;

  /// Stable content-type codes, independent of enum ordering.
  static final _typeToCode = {
    BarcodeValueType.unknown: _unknownCode,
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

  /// Reverse lookup used while decoding native barcode results.
  static final _codeToType = {for (final entry in _typeToCode.entries) entry.value: entry.key};

  /// Platform code of this barcode content type.
  int get code => _typeToCode[this] ?? _unknownCode;

  /// Decodes a barcode content type from its platform code.
  static BarcodeValueType fromCode(int code) => _codeToType[code] ?? BarcodeValueType.unknown;
}
