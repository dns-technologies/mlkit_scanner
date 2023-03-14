/// Ios camera position.
enum IosCameraPosition {
  /// See https://developer.apple.com/documentation/avfoundation/avcapturedevice/position/unspecified.
  unspecified,

  /// See https://developer.apple.com/documentation/avfoundation/avcapturedevice/position/back.
  back,

  /// See https://developer.apple.com/documentation/avfoundation/avcapturedevice/position/front.
  front,
}

extension IosCameraPositionCode on IosCameraPosition {
  /// Code of [position] for transmission over the platform channel.
  static int toCode(IosCameraPosition position) => _positionToCode[position]!;

  /// Position with corresponding [code].
  static IosCameraPosition fromCode(int code) => _codeToPosition[code]!;

  static final _positionToCode = {
    IosCameraPosition.unspecified: 0,
    IosCameraPosition.back: 1,
    IosCameraPosition.front: 2,
  };

  static final _codeToPosition = {
    for (final entry in _positionToCode.entries) entry.value: entry.key,
  };
}
