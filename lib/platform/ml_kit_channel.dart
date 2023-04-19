import 'dart:async';

import 'package:flutter/services.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';
import 'package:mlkit_scanner/models/recognition_type.dart';

/// Platform channel of the MLkit plugin
class MlKitChannel {
  static const _initCameraMethod = 'initCameraPreview';
  static const _disposeMethod = 'dispose';
  static const _toggleFlashMethod = 'toggleFlash';
  static const _startScanMethod = 'startScan';
  static const _cancelScanMethod = 'cancelScan';
  static const _setScanDelayMethod = 'setScanDelay';
  static const _scanResultMethod = 'onScanResult';
  static const _updateConstraintsMethod = 'updateConstraints';
  static const _pauseCameraMethod = 'pauseCameraMethod';
  static const _resumeCameraMethod = 'resumeCameraMethod';
  static const _changeTorchStateMethod = 'changeTorchStateMethod';
  static const _setZoomMethod = 'setZoom';
  static const _setCropAreaMethod = 'setCropAreaMethod';
  static const _getIosAvailableCameras = 'getIosAvailableCameras';
  static const _setIosCamera = 'setIosCamera';

  static MlKitChannel? _instance;
  final MethodChannel _channel = const MethodChannel('mlkit_channel');
  final StreamController<Barcode> _scanResultStreamController =
      StreamController<Barcode>.broadcast();
  final StreamController<bool> _torchToggleStreamController =
      StreamController<bool>.broadcast();

  /// Stream inform when torch change state.
  ///
  /// Work only on IOS
  Stream<bool> get torchToggleStream => _torchToggleStreamController.stream;

  factory MlKitChannel() {
    _instance ??= MlKitChannel._();
    return _instance!;
  }

  MlKitChannel._() {
    _channel.setMethodCallHandler((call) async {
      if (call.method == _scanResultMethod && call.arguments is Map) {
        _scanResultStreamController
            .add(Barcode.fromJson(call.arguments.cast<String, dynamic>()));
      } else if (call.method == _changeTorchStateMethod &&
          call.arguments is bool) {
        _torchToggleStreamController.add(call.arguments);
      }
    });
  }

  /// Initialize camera preview.
  ///
  /// Can throw a [PlatformException] if device has problem with camera, or doesn't have one.
  /// Plugin ask permission to use camera, if user doesn't grant permission also throw a [PlatformException].
  Future<void> initCameraPreview() {
    return _channel.invokeMethod(_initCameraMethod);
  }

  /// Release resources of the camera.
  ///
  /// Must call this method when camera is no longer needed.
  Future<void> dispose() {
    return _channel.invokeMethod(_disposeMethod);
  }

  /// Toggle flash of the device.
  ///
  /// Can throw a [PlatformException] if doesn't have flash.
  Future<void> toggleFlash() {
    return _channel.invokeMethod(_toggleFlashMethod);
  }

  /// Start recognition objects of type [RecognitionType]
  ///
  /// `type` - [RecognitionType], plugin will use MlKit API for this type.
  /// `delay` -  delay in milliseconds between detection for decreasing CPU consumption.
  /// Detection happens every [delay] milliseconds, skipping frames during delay
  /// Can throw [PlatformException] if camera is not initialized.
  Future<Stream<Barcode>> startScan(RecognitionType type, int delay) async {
    final args = {
      'type': type.rawValue,
      'delay': delay,
    };
    await _channel.invokeMethod(_startScanMethod, args);
    return _scanResultStreamController.stream;
  }

  /// Stop recognition of the objects.
  Future<void> cancelScan() {
    return _channel.invokeMethod(_cancelScanMethod);
  }

  /// Set delay between detections when scanning is active.
  ///
  /// `delay` -  delay in milliseconds between detection for decreasing CPU consumption.
  /// Detection happens every [delay] milliseconds, skipping frames during delay
  Future<void> setScanDelay(int delay) {
    return _channel.invokeMethod(_setScanDelayMethod, delay);
  }

  /// Update frame constraints for native platform view.
  ///
  /// Must call when Flutter widget [AndroidView] or [UIkitView] changes size.
  Future<void> updateConstraints(double width, double height) {
    final arg = {
      'width': width,
      'height': height,
    };
    return _channel.invokeMethod(_updateConstraintsMethod, arg);
  }

  /// Pause camera, also pause detection if scanning is active.
  ///
  /// For release resources of the camera use method [dispose].
  Future<void> pauseCamera() {
    return _channel.invokeMethod(_pauseCameraMethod);
  }

  /// Resume camera, also start detection if method [startScan] was called before pause.
  ///
  /// Can throw [PlatformException] if camera is not initialized.
  Future<void> resumeCamera() {
    return _channel.invokeMethod(_resumeCameraMethod);
  }

  /// Sets the camera zoom.
  Future<void> setZoom(double value) {
    return _channel.invokeMethod(_setZoomMethod, value);
  }

  /// Adds overlay to the [CameraPreview] and sets area for recognition
  ///
  /// `rect` - Scanning area of the overlay.
  Future<void> setCropArea(CropRect rect) {
    return _channel.invokeMethod(_setCropAreaMethod, rect.toJson());
  }

  /// Gets all available iOS cameras.
  Future<List<IosCamera>> getIosAvailableCameras() async {
    final availableCameras =
        (await _channel.invokeListMethod<dynamic>(_getIosAvailableCameras))!;
    return availableCameras
        .map((json) => IosCamera.fromJson(Map<String, dynamic>.from(json)))
        .toList();
  }

  /// Sets iOS camera with [position] and [type].
  Future<void> setIosCamera({
    required IosCameraPosition position,
    required IosCameraType type,
  }) {
    return _channel.invokeMethod(_setIosCamera, {
      'position': position.code,
      'type': type.code,
    });
  }
}
