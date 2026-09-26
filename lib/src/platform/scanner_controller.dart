import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:mlkit_scanner/models/barcode.dart';
import 'package:mlkit_scanner/platform/scanner_configuration.dart';
import 'package:mlkit_scanner/platform/scanner_runtime.dart';

/// Ownership and control readiness, independent of native frame availability.
enum ScannerCaptureState {
  /// No ownership; display this widget's retained image when available.
  released,

  /// Ownership is selected, but native controls are still being initialized.
  starting,

  /// Capture settings are acknowledged and focus controls are available.
  ready,
}

/// Owns desired configuration for one scanner widget.
///
/// Accepts complete widget settings. Runtime serializes native application and
/// reports failures to the widget without exposing an imperative public API.
@internal
class BarcodeScannerController {
  /// Shared runtime captured when this controller is constructed.
  final _runtime = ScannerRuntime.instance;

  /// Logical widget identity; independent of the shared texture identity.
  final int viewId;

  /// Receives failures belonging to this widget while its controller is alive.
  final void Function(Object, StackTrace)? onError;

  /// Copies this widget's preview before releasing ownership or changing paused controls.
  final Future<void> Function()? retainPreview;

  /// Desired settings retained independently of current native ownership.
  ScannerConfiguration _configuration;

  /// Route permission to deliver results, independent of user setting validation.
  bool _foreground = true;

  /// Delivers admitted barcodes to the owning widget.
  final ValueChanged<Barcode>? onScan;

  /// Delivers native torch changes to the owning widget.
  final ValueChanged<bool>? onTorchChanged;

  final _captureState = ValueNotifier(ScannerCaptureState.released);

  /// Synchronous guard against commands or events after dispose is requested.
  bool _disposed = false;

  /// Whether this controller has permanently stopped accepting work.
  bool get isDisposed => _disposed;

  /// Desired settings with recognition suppressed while a modal covers the route.
  ScannerConfiguration get configuration => _foreground ? _configuration : _configuration.copyWith(scanEnabled: false);

  /// Drives snapshot retention and focus without delaying a live shared preview.
  ValueListenable<ScannerCaptureState> get captureState => _captureState;

  BarcodeScannerController({
    required this.viewId,
    this.onError,
    this.onScan,
    this.onTorchChanged,
    this.retainPreview,
    ScannerConfiguration configuration = const ScannerConfiguration(),
  }) : _configuration = configuration;

  /// Unregisters this controller and releases its capture if selected.
  /// Later configuration and event calls have no effect.
  void dispose() {
    if (_disposed) return;

    _disposed = true;
    unawaited(
      _runtime.unregister(this).catchError((Object error, StackTrace stack) {
        FlutterError.reportError(
          FlutterErrorDetails(
            exception: error,
            stack: stack,
            library: 'mlkit_scanner',
            context: ErrorDescription('while releasing a scanner widget'),
          ),
        );
      }),
    );
    // An event or preview listener may dispose its own controller during delivery.
    scheduleMicrotask(() {
      _captureState.value = ScannerCaptureState.released;
      _captureState.dispose();
    });
  }

  /// Forwards a native recognition result to the owning live widget.
  void addScanResult(Barcode barcode) {
    if (_disposed) return;
    onScan?.call(barcode);
  }

  /// Forwards a native torch change without altering the desired torch setting.
  void addTorchState(bool enabled) {
    if (_disposed) return;
    onTorchChanged?.call(enabled);
  }

  /// Updates readiness for this widget without changing the shared texture lifetime.
  void setCaptureState(ScannerCaptureState state) {
    if (_disposed) return;
    _captureState.value = state;
  }

  /// Suspends recognition without releasing capture or altering retained intent.
  void setForeground(bool foreground) {
    if (_disposed || _foreground == foreground) return;
    _foreground = foreground;
    _runtime.configurationChanged(this);
  }

  /// Reports a live widget's failure, falling back to Flutter when unhandled.
  void reportError(Object error, StackTrace stack) {
    if (_disposed) return;
    if (onError case final callback?) {
      callback(error, stack);
    } else {
      FlutterError.reportError(FlutterErrorDetails(exception: error, stack: stack, library: 'mlkit_scanner'));
    }
  }

  /// Validates and publishes one complete snapshot, including nullable resets.
  /// No state is changed if any setting is invalid.
  void applyConfiguration(ScannerConfiguration next) {
    if (_disposed) return;
    RangeError.checkValueInInterval(next.scanDelay, 0, 0x7fffffff, 'scanDelay');
    if (!next.zoomRatio.isFinite || next.zoomRatio <= 0) {
      throw ArgumentError.value(next.zoomRatio, 'zoomRatio');
    }
    final crop = next.cropRect;
    if (crop != null && !crop.isValid) {
      throw ArgumentError.value(crop, 'cropRect');
    }
    if (next.iosCamera != null && defaultTargetPlatform != TargetPlatform.iOS) {
      throw UnsupportedError('Camera selection is only supported on iOS');
    }
    if (_configuration.hasSameSettings(next)) return;
    _configuration = next;
    _runtime.configurationChanged(this);
  }

  /// Internally acquires shared camera ownership when this widget becomes visible.
  Future<void> capture() => _runtime.capture(this);

  /// Releases ownership while the runtime keeps the stream warm for a successor.
  Future<void> release() => _runtime.release(this);
}
