/// Physical position of an iOS camera.
enum IosCameraPosition {
  /// Camera position is not specified.
  unspecified,

  /// Camera faces away from the user.
  back,

  /// Camera faces the user.
  front,
}

/// Converts between [IosCameraPosition] values and native platform codes.
extension IosCameraPositionCode on IosCameraPosition {
  /// Code of position for transmission over the platform channel.
  int get code => _positionToCode[this]!;

  /// Returns the position corresponding to the [code].
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
