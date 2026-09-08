import 'dart:async';

import 'package:flutter/services.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';
import 'package:mlkit_scanner/platform/scanner_configuration.dart';

/// A native event addressed to its source preview.
typedef ScannerEvent<T> = ({int viewId, T value});

/// Typed access to the scanner platform channel.
class MlKitChannel {
  static MlKitChannel? _instance;
  final MethodChannel _channel = const MethodChannel('mlkit_channel');
  final StreamController<ScannerEvent<Barcode>> _scanResultStreamController = StreamController<ScannerEvent<Barcode>>.broadcast();
  final StreamController<ScannerEvent<bool>> _torchToggleStreamController = StreamController<ScannerEvent<bool>>.broadcast();

  /// Returns the shared channel instance used by all scanner widgets.
  factory MlKitChannel() {
    _instance ??= MlKitChannel._();
    return _instance!;
  }

  /// Creates the shared channel and registers callbacks from native platforms.
  MlKitChannel._() {
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'onScanResult') {
        final event = _decodeClientEvent(
          call.arguments,
          'barcode',
          (value) => Barcode.fromJson(Map<String, dynamic>.from(value as Map)),
        );
        if (event != null) _scanResultStreamController.add(event);
      } else if (call.method == 'changeTorchStateMethod') {
        final event = _decodeClientEvent(
          call.arguments,
          'value',
          (value) => value as bool,
        );
        if (event != null) _torchToggleStreamController.add(event);
      }
    });
  }

  /// Decodes a view-scoped native event and ignores malformed payloads.
  ScannerEvent<T>? _decodeClientEvent<T>(
    Object? arguments,
    String valueKey,
    T Function(Object? value) decodeValue,
  ) {
    if (arguments is! Map) return null;
    final viewId = arguments['viewId'];
    if (viewId is! int || !arguments.containsKey(valueKey)) return null;

    try {
      return (viewId: viewId, value: decodeValue(arguments[valueKey]));
    } on Object {
      return null;
    }
  }

  /// Invokes a native command and maps camera error code 9 to its typed form.
  Future<void> _invokeVoidMethod(String method, Object? arguments) async {
    try {
      await _channel.invokeMethod<void>(method, arguments);
    } on PlatformException catch (error, stackTrace) {
      if (error.code == CameraControlException.errorCode) {
        Error.throwWithStackTrace(
          CameraControlException.fromPlatformException(error),
          stackTrace,
        );
      }
      rethrow;
    }
  }

  /// Applies a complete Dart snapshot after selecting the native preview.
  /// Completes after SDK configuration; release may interrupt this operation.
  Future<void> captureCamera({required int viewId, required ScannerConfiguration configuration}) =>
      _invokeVoidMethod('captureCamera', {'viewId': viewId, 'configuration': configuration.toJson()});

  /// Releases the scanner and acknowledges cancellation of its unfinished work.
  Future<void> releaseCamera() => _invokeVoidMethod('releaseCamera', null);

  /// Changes only zoom on the selected scanner, without restarting preview.
  Future<void> setZoomRatio(double value) => _invokeVoidMethod('setZoomRatio', {'value': value});

  /// Sets the selected scanner's torch to an absolute state.
  Future<void> setTorch(bool enabled) => _invokeVoidMethod('toggleFlash', {'value': enabled});

  /// Updates recognition geometry without recapturing the camera.
  Future<void> setCropArea(CropRect cropRect) => _invokeVoidMethod('setCropArea', {'cropRect': cropRect.toJson()});

  /// Updates the recognition cooldown.
  Future<void> setScanDelay(int delay) => _invokeVoidMethod('setScanDelay', {'delay': delay});

  /// Starts barcode recognition on the selected preview.
  Future<void> startScan(int delay) => _invokeVoidMethod('startScan', {'type': 0, 'delay': delay});

  /// Stops recognition without stopping preview.
  Future<void> cancelScan() => _invokeVoidMethod('cancelScan', null);

  /// All recognized barcodes, tagged with the native source view.
  Stream<ScannerEvent<Barcode>> get scanResults => _scanResultStreamController.stream;

  /// All iOS torch changes, tagged with the native source view.
  Stream<ScannerEvent<bool>> get torchToggleStream => _torchToggleStreamController.stream;

  /// Returns all iOS cameras supported by the native implementation.
  Future<List<IosCamera>> getIosAvailableCameras() async {
    final availableCameras = (await _channel.invokeListMethod<dynamic>('getIosAvailableCameras'))!;
    return availableCameras.map((json) => IosCamera.fromJson(Map<String, dynamic>.from(json))).toList();
  }
}
