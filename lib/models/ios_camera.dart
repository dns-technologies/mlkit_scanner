import 'ios_camera_position.dart';
import 'ios_camera_type.dart';

/// Camera selection by device type and position.
class IosCamera {
  const IosCamera({required this.type, required this.position});

  factory IosCamera.fromJson(Map<String, dynamic> json) {
    return IosCamera(type: IosCameraTypeCode.fromCode(json['type']), position: IosCameraPositionCode.fromCode(json['position']));
  }

  /// Physical lens or combined camera device to select.
  final IosCameraType type;

  /// Direction the camera faces relative to the user.
  final IosCameraPosition position;
}
