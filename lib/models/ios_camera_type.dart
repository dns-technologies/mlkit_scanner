/// Ios camera type.
enum IosCameraType {
  /// See https://developer.apple.com/documentation/avfoundation/avcapturedevice/devicetype/2361449-builtinwideanglecamera.
  builtInWideAngleCamera,

  /// See https://developer.apple.com/documentation/avfoundation/avcapturedevice/devicetype/2361478-builtintelephotocamera.
  builtInTelephotoCamera,

  /// See https://developer.apple.com/documentation/avfoundation/avcapturedevice/devicetype/2727142-builtindualcamera.
  builtInDualCamera,

  /// See https://developer.apple.com/documentation/avfoundation/avcapturedevice/devicetype/3377622-builtinultrawidecamera.
  builtInUltraWideCamera,

  /// See https://developer.apple.com/documentation/avfoundation/avcapturedevice/devicetype/3377620-builtindualwidecamera.
  builtInDualWideCamera,

  /// See https://developer.apple.com/documentation/avfoundation/avcapturedevice/devicetype/3377621-builtintriplecamera.
  builtInTripleCamera,
}

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
