import 'package:mlkit_scanner/models/ios_camera.dart';
import 'package:mlkit_scanner/platform/ml_kit_channel.dart';

/// Contains useful methods that can be accessed regardless of the state of the camera.
abstract class MLKitUtils {
  /// Shared transport used for queries that do not require camera ownership.
  static final MlKitChannel _channel = MlKitChannel();

  static Duration _cameraShutdownDelay = const Duration(milliseconds: 300);

  /// App-wide delay before releasing resources after the active capture is
  /// released. Defaults to 300 milliseconds.
  ///
  /// The camera keeps streaming without recognition during this grace period.
  /// Only a new capture cancels shutdown; hidden registrations do not.
  static Duration get cameraShutdownDelay => _cameraShutdownDelay;

  /// Sets the delay used by the next scheduled shutdown. Pending timers keep
  /// their original delay. [Duration.zero] disables the grace period.
  ///
  /// Throws [ArgumentError] if [value] is negative.
  static set cameraShutdownDelay(Duration value) {
    if (value.isNegative) {
      throw ArgumentError.value(value, 'cameraShutdownDelay', 'Must not be negative');
    }
    _cameraShutdownDelay = value;
  }

  /// Gets all available iOS cameras.
  static Future<List<IosCamera>> getIosAvailableCameras() => _channel.getIosAvailableCameras();
}
