import 'ios_camera_position.dart';
import 'ios_camera_type.dart';

/// Describes an iOS capture device supported by the plugin.
class IosCamera {
  /// Camera type.
  final IosCameraType type;

  /// Camera position.
  final IosCameraPosition position;

  /// Creates an iOS camera descriptor.
  const IosCamera({required this.type, required this.position});

  /// Creates a camera descriptor from its platform-channel representation.
  factory IosCamera.fromJson(Map<String, dynamic> json) {
    return IosCamera(type: IosCameraTypeCode.fromCode(json['type']), position: IosCameraPositionCode.fromCode(json['position']));
  }
}
