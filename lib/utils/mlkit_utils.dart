import 'package:mlkit_scanner/models/ios_camera.dart';
import 'package:mlkit_scanner/platform/ml_kit_channel.dart';

/// Contains useful methods that can be accessed regardless of the state of the camera.
class MLKitUtils {
  final MlKitChannel _MLKitChannel;

  MLKitUtils() : _MLKitChannel = MlKitChannel();

  /// Gets all available iOS cameras.
  Future<List<IosCamera>> getIosAvailableCameras() =>
      _MLKitChannel.getIosAvailableCameras();
}
