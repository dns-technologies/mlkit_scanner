import 'package:mlkit_scanner/models/crop_rect.dart';

/// Parameters for initializing the scanner.
abstract class InitialArguments {
  /// Optional initial scanner overlay with [CropRect] of the detection area.
  final CropRect? initialCropRect;

  const InitialArguments({this.initialCropRect});

  /// Converting an object into a format for transferring to native.
  Map<String, dynamic> toJson();
}
