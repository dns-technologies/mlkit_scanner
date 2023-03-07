enum IosCameraPosition {
  back,

  front,
}

extension IosCameraPositionCode on IosCameraPosition {
  static final _positionToCode = {
    IosCameraPosition.back: 1,
    IosCameraPosition.front: 2,
  };

  static final _codeToPosition = {for (final entry in _positionToCode.entries) entry.value: entry.key};

  static IosCameraPosition fromCode(int code) => _codeToPosition[code]!;

  static int toCode(IosCameraPosition type) => _positionToCode[type]!;
}
