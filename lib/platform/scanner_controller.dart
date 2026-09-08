import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:mlkit_scanner/models/barcode.dart';
import 'package:mlkit_scanner/models/crop_rect.dart';
import 'package:mlkit_scanner/models/ios_camera.dart';
import 'package:mlkit_scanner/models/ios_camera_position.dart';
import 'package:mlkit_scanner/models/ios_camera_type.dart';
import 'package:mlkit_scanner/platform/scanner_configuration.dart';
import 'package:mlkit_scanner/platform/scanner_runtime.dart';

/// Owns desired configuration for an already registered native view.
///
/// Setters validate and publish Dart state; their futures do not acknowledge SDK
/// work. Runtime serializes native application and reports failures to Flutter.
class BarcodeScannerController {
  final _runtime = ScannerRuntime.instance;

  /// Native view associated at construction, after platform registration.
  final int viewId;
  ScannerConfiguration _configuration;
  final _states = StreamController<ScannerConfiguration>.broadcast(sync: true);
  final _scans = StreamController<Barcode>.broadcast(sync: true);
  final _torch = StreamController<bool>.broadcast(sync: true);

  /// Latest desired settings, also retained while another scanner is active.
  ScannerConfiguration get configuration => _configuration;

  /// Full desired-state snapshots, published after updating [configuration].
  /// Each subscriber owns its subscription; native application is asynchronous.
  Stream<ScannerConfiguration> get states => _states.stream;

  /// Native scan results forwarded while this controller is selected.
  Stream<Barcode> get scanResults => _scans.stream;

  /// Native torch changes while this view is connected (currently iOS only).
  Stream<bool> get torchToggleStream => _torch.stream;

  /// Registers a controller for an existing native preview without capturing the camera.
  BarcodeScannerController({
    required this.viewId,
    ScannerConfiguration configuration = const ScannerConfiguration(),
  }) : _configuration = configuration {
    _runtime.register(this);
  }

  /// Unregisters this controller, releases its camera and closes its streams.
  /// Later configuration and event calls have no effect.
  void dispose() {
    if (_states.isClosed) return;
    unawaited(_runtime.unregister(this));
    unawaited(_states.close());
    // A scan or torch listener may dispose its own controller during delivery.
    scheduleMicrotask(() {
      unawaited(_scans.close());
      unawaited(_torch.close());
    });
  }

  /// Forwards a native recognition result to this controller's listeners.
  void addScanResult(Barcode barcode) {
    if (_states.isClosed) return;
    _scans.add(barcode);
  }

  /// Forwards a native torch change without altering the desired torch setting.
  void addTorchState(bool enabled) {
    if (_states.isClosed) return;
    _torch.add(enabled);
  }

  Future<void> _update(ScannerConfiguration next) async {
    if (_states.isClosed) return;
    _configuration = next;
    _states.add(next);
  }

  /// Retains the desired torch state even while another controller owns the camera.
  Future<void> toggleFlash() async {
    if (_states.isClosed) return;
    await _update(_configuration.copyWith(torchEnabled: !_configuration.torchEnabled));
  }

  /// Starts barcode recognition with a successful-result cooldown in milliseconds.
  Future<void> startScan(int delay) async {
    if (_states.isClosed) return;
    RangeError.checkValueInInterval(delay, 0, 0x7fffffff, 'delay');
    await _update(_configuration.copyWith(scanEnabled: true, scanDelay: delay));
  }

  /// Requests native recognition to stop; runtime queues the configuration change.
  Future<void> cancelScan() async {
    if (_states.isClosed) return;
    await _update(_configuration.copyWith(scanEnabled: false));
  }

  /// Pauses camera work while retaining zoom, torch and recognition settings.
  /// A hidden scanner only retains pause intent and does not affect another view.
  Future<void> pauseCamera() => _update(_configuration.copyWith(cameraPaused: true));

  /// Resumes camera work with the retained settings and recognition intent.
  /// A hidden scanner waits until its route and app are visible again.
  Future<void> resumeCamera() => _update(_configuration.copyWith(cameraPaused: false));

  /// Retains the successful-recognition cooldown in milliseconds.
  Future<void> setDelay(int delay) async {
    if (_states.isClosed) return;
    RangeError.checkValueInInterval(delay, 0, 0x7fffffff, 'delay');
    await _update(_configuration.copyWith(scanDelay: delay));
  }

  /// Retains a positive absolute zoom ratio; inactive controllers do not touch the camera.
  Future<void> setZoomRatio(double value) async {
    if (_states.isClosed) return;
    if (!value.isFinite || value <= 0) {
      throw ArgumentError.value(value, 'zoomRatio');
    }
    await _update(_configuration.copyWith(zoomRatio: value));
  }

  /// Sets normalized recognition geometry relative to the preview.
  /// Only its visible intersection is analyzed; hidden scanners retain the area.
  Future<void> setCropArea(CropRect rect) async {
    if (_states.isClosed) return;
    if (!rect.scaleWidth.isFinite ||
        rect.scaleWidth <= 0 ||
        !rect.scaleHeight.isFinite ||
        rect.scaleHeight <= 0 ||
        !rect.offsetX.isFinite ||
        !rect.offsetY.isFinite) {
      throw ArgumentError.value(rect, 'cropRect');
    }
    await _update(_configuration.copyWith(cropRect: rect));
  }

  /// Selects an iOS camera, retaining the choice while this scanner is hidden.
  /// Throws [UnsupportedError] on other platforms.
  Future<void> setIosCamera({required IosCameraPosition position, required IosCameraType type}) async {
    if (_states.isClosed) return;
    if (defaultTargetPlatform != TargetPlatform.iOS) {
      throw UnsupportedError('Camera selection is only supported on iOS');
    }
    await _update(_configuration.copyWith(iosCamera: IosCamera(position: position, type: type)));
  }
}
