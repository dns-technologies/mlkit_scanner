import 'package:mlkit_scanner/models/barcode.dart';

import 'scanner_configuration.dart';

/// Ownership and control readiness, independent of native frame availability.
enum ScannerCaptureState {
  /// No active ownership of the camera.
  released,

  /// Ownership is selected, but native controls are still being initialized.
  starting,

  /// Capture settings are acknowledged and focus controls are available.
  ready,
}

/// Camera consumer contract for desired settings, capture readiness and result delivery.
abstract interface class ScannerConsumer {
  /// Stable identity of this camera consumer.
  int get viewId;

  /// Desired settings, including the consumer's current recognition permission.
  ScannerConfiguration get configuration;

  /// Whether this consumer has permanently stopped accepting work.
  bool get isDisposed;

  /// Callback that completes when the current preview image has been retained.
  Future<void> Function()? get retainPreview;

  /// Updates this consumer's ownership and control readiness.
  void setCaptureState(ScannerCaptureState state);

  /// Delivers an admitted recognition result.
  void addScanResult(Barcode barcode);

  /// Delivers a native torch change without changing desired settings.
  void addTorchState(bool enabled);

  /// Reports an operation failure while the consumer remains alive.
  void reportError(Object error, StackTrace stack);
}
