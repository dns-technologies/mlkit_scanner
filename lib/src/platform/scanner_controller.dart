import 'dart:async';
import 'dart:ui';

import 'package:flutter/foundation.dart';
import 'package:mlkit_scanner/models/barcode.dart';
import 'package:mlkit_scanner/platform/scanner_configuration.dart';
import 'package:mlkit_scanner/platform/scanner_consumer.dart';
import 'package:mlkit_scanner/platform/scanner_preview.dart';
import 'package:mlkit_scanner/platform/scanner_runtime.dart';

export 'package:mlkit_scanner/platform/scanner_consumer.dart' show ScannerCaptureState;

/// Internal facade for camera ownership, desired settings and recognition callbacks.
///
/// Accepts complete camera and recognition settings.
@internal
class BarcodeScannerController implements ScannerConsumer {
  BarcodeScannerController({
    required this.viewId,
    this.onError,
    this.onScan,
    this.onTorchChanged,
    this.retainPreview,
    ScannerConfiguration configuration = const ScannerConfiguration(),
    ScannerRuntime? runtime,
  }) : _configuration = configuration,
       _runtime = runtime ?? ScannerRuntime.instance;

  /// Runtime used for registration and camera operations.
  final ScannerRuntime _runtime;

  /// Registration shared by initialization and capture, without owning hardware.
  Future<void>? _registration;

  @override
  final int viewId;

  /// Receives operation failures while this controller is alive.
  final void Function(Object, StackTrace)? onError;

  @override
  final Future<void> Function()? retainPreview;

  /// Desired settings retained independently of current native ownership.
  ScannerConfiguration _configuration;

  /// Foreground permission to deliver recognition results.
  bool _foreground = true;

  /// Receives admitted barcode results.
  final ValueChanged<Barcode>? onScan;

  /// Receives native torch changes.
  final ValueChanged<bool>? onTorchChanged;

  /// Publishes ownership changes without exposing a writable notifier.
  final _captureState = ValueNotifier(ScannerCaptureState.released);

  /// Synchronous guard against commands or events after dispose is requested.
  bool _disposed = false;

  @override
  bool get isDisposed => _disposed;

  @override
  ScannerConfiguration get configuration => _foreground ? _configuration : _configuration.copyWith(scanEnabled: false);

  /// Observable camera ownership and control readiness.
  ValueListenable<ScannerCaptureState> get captureState => _captureState;

  /// Shared texture metadata; callers can observe but cannot replace the output.
  ValueListenable<ScannerPreviewDescription?> get preview => _runtime.preview;

  /// Whether this controller owns a capture that still admits camera operations.
  bool get isCurrent => _runtime.isCurrent(this);

  /// Whether this controller may restore capture as the last owner of an idle camera.
  bool get canRestoreCapture => _runtime.canRestoreCapture(this);

  /// Registers this consumer once; capture remains a separate operation.
  Future<void> initialize() {
    if (_disposed) return Future<void>.value();
    return _registration ??= _runtime.register(this);
  }

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

  @override
  void addScanResult(Barcode barcode) {
    if (_disposed) return;
    onScan?.call(barcode);
  }

  @override
  void addTorchState(bool enabled) {
    if (_disposed) return;
    onTorchChanged?.call(enabled);
  }

  @override
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

  @override
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

  /// Acquires camera ownership, revoking the previous owner's capture immediately.
  Future<void> capture() => _runtime.capture(this);

  /// Releases ownership while keeping the camera available for a successor.
  Future<void> release() => _runtime.release(this);

  /// Revokes capture and awaits a physical camera stop.
  Future<void> suspend() => _runtime.suspend(this);

  /// Applies a focus request only while this controller owns the camera.
  Future<void> focus({required bool locked}) => _runtime.focus(this, locked: locked);

  /// Retains layout dimensions for recognition and focus mapping.
  void updateGeometry(Size size) => _runtime.updateGeometry(this, size);
}
