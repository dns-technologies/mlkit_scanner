import 'package:mlkit_scanner/models/crop_rect.dart';
import 'package:mlkit_scanner/models/initial_arguments.dart';

/// Parameters for initializing the scanner on Android.
class AndroidInitialArguments extends InitialArguments {
  /// Optional initial zoom.
  final double? initialZoom;

  const AndroidInitialArguments({this.initialZoom, CropRect? initialCropRect}) : super(initialCropRect: initialCropRect);

  @override
  Map<String, dynamic> toJson() {
    return {
      'initialZoom': initialZoom,
      'initialCropRect': initialCropRect?.toJson(),
    };
  }
}
