import 'package:mlkit_scanner/mlkit_scanner.dart';

enum IosCameraType {
  builtInWideAngleCamera,
  builtInTelephotoCamera,
  builtInDualCamera,
  builtInUltraWideCamera,
  builtInDualWideCamera,
  builtInTripleCamera,
  builtInTrueDepthCamera,
  builtInLiDARDepthCamera,
}

extension IosCameraTypeCode on IosCameraType {
  static final _typeToCode = {
    IosCameraType.builtInWideAngleCamera: 1,
    IosCameraType.builtInTelephotoCamera: 2,
    IosCameraType.builtInDualCamera: 3,
    IosCameraType.builtInUltraWideCamera: 4,
    IosCameraType.builtInDualWideCamera: 5,
    IosCameraType.builtInTripleCamera: 6,
    IosCameraType.builtInTrueDepthCamera: 7,
    IosCameraType.builtInLiDARDepthCamera: 8,
  };

  static final _codeToType = {for (final entry in _typeToCode.entries) entry.value: entry.key};

  static IosCameraType fromCode(int code) => _codeToType[code]!;

  static int toCode(IosCameraType type) => _typeToCode[type]!;
}
