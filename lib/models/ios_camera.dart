import 'package:mlkit_scanner/mlkit_scanner.dart';

class IosCamera {
  final IosCameraType type;

  final IosCameraPosition position;

  const IosCamera({required this.type, required this.position});

  factory IosCamera.fromJson(Map<String, dynamic> json) {
    return IosCamera(
      type: IosCameraTypeCode.fromCode(json['type']),
      position: IosCameraPositionCode.fromCode(json['position']),
    );
  }
}
