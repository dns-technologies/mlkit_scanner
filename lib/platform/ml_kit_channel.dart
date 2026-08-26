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
  final StreamController<_ClientEvent<Barcode>> _scanResultStreamController =
      StreamController<_ClientEvent<Barcode>>.broadcast();
  final StreamController<_ClientEvent<bool>> _torchToggleStreamController =
      StreamController<_ClientEvent<bool>>.broadcast();

  factory MlKitChannel() {
    _instance ??= MlKitChannel._();
    return _instance!;
  }

  MlKitChannel._() {
    _channel.setMethodCallHandler((call) async {
      if (call.method == _scanResultMethod) {
        final event = _decodeClientEvent(
          call.arguments,
          'barcode',
          (value) => Barcode.fromJson(Map<String, dynamic>.from(value as Map)),
        );
        if (event != null) _scanResultStreamController.add(event);
      } else if (call.method == _changeTorchStateMethod) {
        final event = _decodeClientEvent(
          call.arguments,
          'value',
          (value) => value as bool,
        );
        if (event != null) _torchToggleStreamController.add(event);
      }
    });
  }

  _ClientEvent<T>? _decodeClientEvent<T>(
    Object? arguments,
    String valueKey,
    T Function(Object? value) decodeValue,
  ) {
    if (arguments is! Map) return null;
    final viewId = arguments['viewId'];
    if (viewId is! int || !arguments.containsKey(valueKey)) return null;

    try {
      return _ClientEvent(viewId, decodeValue(arguments[valueKey]));
    } on Object {
      return null;
    }
  }

  /// Initialize camera preview.
  ///
  /// Can throw a [PlatformException] if device has problem with camera, or doesn't have one.
  /// Plugin ask permission to use camera, if user doesn't grant permission also throw a [PlatformException].
  Future<void> initCameraPreview({
    required int viewId,
    double? initialZoom,
    CropRect? initialCropRect,
    IosCamera? initialCamera,
  }) {
    final arguments = <String, Object?>{
      'viewId': viewId,
      if (initialZoom != null) 'initialZoom': initialZoom,
      if (initialCropRect != null) 'initialCropRect': initialCropRect.toJson(),
      if (initialCamera != null)
        'initialCamera': {
          'position': initialCamera.position.code,
          'type': initialCamera.type.code,
        },
    };
    return _channel.invokeMethod(_initCameraMethod, arguments);
  }

  /// Removes one platform view from the shared native scanner session.
  Future<void> dispose({required int viewId}) {
    return _channel.invokeMethod(_disposeMethod, {'viewId': viewId});
  }

  /// Toggle flash of the device.
  ///
  /// Can throw a [PlatformException] if doesn't have flash.
  Future<void> toggleFlash() {
    return _channel.invokeMethod(_toggleFlashMethod);
  }

  /// Starts recognition requested by the platform view identified by [viewId].
  ///
  /// `type` - [RecognitionType], plugin will use MlKit API for this type.
  /// `delay` -  delay in milliseconds between detection for decreasing CPU consumption.
  /// Detection happens every [delay] milliseconds, skipping frames during delay.
  /// Only the current scanner view receives native scan events.
  /// Can throw [PlatformException] if camera is not initialized.
  Future<void> startScan(
    RecognitionType type,
    int delay, {
    required int viewId,
  }) {
    final args = {
      'viewId': viewId,
      'type': type.rawValue,
      'delay': delay,
    };
    return _channel.invokeMethod(_startScanMethod, args);
  }

  /// Reports recognized barcodes for one platform view.
  Stream<Barcode> scanResults(int viewId) {
    return _scanResultStreamController.stream
        .where((event) => event.viewId == viewId)
        .map((event) => event.value);
  }

  /// Reports iOS torch changes for one platform view.
  Stream<bool> torchToggleStream(int viewId) {
    return _torchToggleStreamController.stream
        .where((event) => event.viewId == viewId)
        .map((event) => event.value);
  }

  /// Stops recognition requested by the platform view identified by [viewId].
  Future<void> cancelScan({required int viewId}) {
    return _channel.invokeMethod(_cancelScanMethod, {'viewId': viewId});
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

  /// Pauses camera work requested by the platform view identified by [viewId].
  ///
  /// Other registered scanner views keep their independent lifecycle intent.
  /// For release resources of the camera use method [dispose].
  Future<void> pauseCamera({required int viewId}) {
    return _channel.invokeMethod(_pauseCameraMethod, {'viewId': viewId});
  }

  /// Resumes camera work requested by the platform view identified by [viewId].
  ///
  /// Detection also resumes if [startScan] was previously requested by this view.
  /// Can throw [PlatformException] if camera is not initialized.
  Future<void> resumeCamera({required int viewId}) {
    return _channel.invokeMethod(_resumeCameraMethod, {'viewId': viewId});
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

class _ClientEvent<T> {
  final int viewId;
  final T value;

  const _ClientEvent(this.viewId, this.value);
}
