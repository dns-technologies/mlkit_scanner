/// Hardware type of an iOS camera.
enum IosCameraType {
  /// Single wide-angle camera.
  builtInWideAngleCamera,

  /// Single telephoto camera.
  builtInTelephotoCamera,

  /// Combined dual-camera device.
  builtInDualCamera,

  /// Single ultra-wide camera.
  builtInUltraWideCamera,

  /// Combined wide and ultra-wide camera device.
  builtInDualWideCamera,

  /// Combined wide, ultra-wide, and telephoto camera device.
  builtInTripleCamera,
}

/// Converts between [IosCameraType] values and native platform codes.
extension IosCameraTypeCode on IosCameraType {
  /// Code of type for transmission over the platform channel.
  int get code => _typeToCode[this]!;

  /// Returns the type corresponding to the [code].
  static IosCameraType fromCode(int code) => _codeToType[code]!;

  static final _typeToCode = {
    IosCameraType.builtInWideAngleCamera: 0,
    IosCameraType.builtInTelephotoCamera: 1,
    IosCameraType.builtInDualCamera: 2,
    IosCameraType.builtInUltraWideCamera: 3,
    IosCameraType.builtInDualWideCamera: 4,
    IosCameraType.builtInTripleCamera: 5,
  };

  static final _codeToType = {
    for (final entry in _typeToCode.entries) entry.value: entry.key,
  };
}
