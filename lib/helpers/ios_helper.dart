import 'package:mlkit_scanner/models/ios_camera.dart';
import 'package:mlkit_scanner/platform/ml_kit_channel.dart';

/// Contains useful methods for the iOS platform, which can be accessed before the camera is initialized.
class IosHelper {
  final MlKitChannel _mlKitChannel;

  IosHelper() : _mlKitChannel = MlKitChannel();

  /// Gets all available iOS cameras.
  Future<List<IosCamera>> getIosAvailableCameras() => _mlKitChannel.getIosAvailableCameras();
}
