import 'package:mlkit_scanner/mlkit_scanner.dart';

/// Ios camera info.
class IosCamera {
  /// Camera type.
  final IosCameraType type;

  /// Camera position.
  final IosCameraPosition position;

  const IosCamera({
    required this.type,
    required this.position,
  });

  factory IosCamera.fromJson(Map<String, dynamic> json) {
    return IosCamera(
      type: IosCameraTypeCode.fromCode(json['type']),
      position: IosCameraPositionCode.fromCode(json['position']),
    );
  }

  Map<String, dynamic> toJson() => {
        'position': IosCameraPositionCode.toCode(position),
        'type': IosCameraTypeCode.toCode(type),
      };
}
