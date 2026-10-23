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
  /// AVFoundation position codes sent over the platform channel.
  static final _positionToCode = {IosCameraPosition.unspecified: 0, IosCameraPosition.back: 1, IosCameraPosition.front: 2};

  /// Reverse lookup for native camera discovery results.
  static final _codeToPosition = {for (final entry in _positionToCode.entries) entry.value: entry.key};

  /// Code of position for transmission over the platform channel.
  int get code => _positionToCode[this]!;

  /// Decodes a supported native position; [code] must belong to this mapping.
  static IosCameraPosition fromCode(int code) => _codeToPosition[code]!;
}
