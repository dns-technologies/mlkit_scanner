import 'package:mlkit_scanner/models/crop_rect.dart';
import 'package:mlkit_scanner/models/initial_arguments.dart';
import 'package:mlkit_scanner/models/ios_camera.dart';
import 'package:mlkit_scanner/models/ios_camera_position.dart';
import 'package:mlkit_scanner/models/ios_camera_type.dart';

/// Parameters for initializing the scanner on iOS.
class IosInitialArguments extends InitialArguments {
  /// Optional initial zoom.
  final double? initialZoom;

  /// Optional initial camera.
  final IosCamera? initialCamera;

  const IosInitialArguments({this.initialZoom, this.initialCamera, CropRect? initialCropRect}) : super(initialCropRect: initialCropRect);

  @override
  Map<String, dynamic> toJson() {
    return {
      'initialZoom': initialZoom,
      'initialCropRect': initialCropRect?.toJson(),
      'initialCamera': initialCamera != null
          ? {
              'position': initialCamera!.position.code,
              'type': initialCamera!.type.code,
            }
          : null,
    };
  }
}
