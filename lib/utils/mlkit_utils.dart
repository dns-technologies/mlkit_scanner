import 'package:mlkit_scanner/models/ios_camera.dart';
import 'package:mlkit_scanner/platform/ml_kit_channel.dart';

/// Contains useful methods that can be accessed regardless of the state of the camera.
abstract class MLKitUtils {
  /// Shared transport used for queries that do not require camera ownership.
  static final MlKitChannel _channel = MlKitChannel();

  /// Gets all available iOS cameras.
  static Future<List<IosCamera>> getIosAvailableCameras() => _channel.getIosAvailableCameras();
}
