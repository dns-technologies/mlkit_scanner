import 'dart:async';

import 'package:flutter/services.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';
import 'package:mlkit_scanner/models/recognition_type.dart';

/// Typed access to the scanner platform channel.
class MlKitChannel {
  static const _captureCameraMethod = 'captureCamera';
  static const _releaseCameraMethod = 'releaseCamera';
  static const _toggleFlashMethod = 'toggleFlash';
  static const _startScanMethod = 'startScan';
  static const _cancelScanMethod = 'cancelScan';
  static const _setScanDelayMethod = 'setScanDelay';
  static const _scanResultMethod = 'onScanResult';
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

  /// Returns the shared channel instance used by all scanner widgets.
  factory MlKitChannel() {
    _instance ??= MlKitChannel._();
    return _instance!;
  }

  /// Creates the shared channel and registers callbacks from native platforms.
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

  /// Decodes a view-scoped native event and ignores malformed payloads.
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

  /// Transfers camera ownership to [viewId] and restores its retained state.
  ///
  /// The first call starts initialization owned by that view. A later call for
  /// the same view awaits the same in-flight initialization. Losing ownership
  /// does not cancel it, but prevents its state from being applied to another
  /// active view.
  /// Throws [CameraControlException] when opening the camera or applying its
  /// retained zoom or torch state fails.
  Future<void> captureCamera({required int viewId}) {
    return _invokeVoidMethod(_captureCameraMethod, {'viewId': viewId});
  }

  /// Releases camera ownership if [viewId] still owns it.
  Future<void> releaseCamera({required int viewId}) {
    return _invokeVoidMethod(_releaseCameraMethod, {'viewId': viewId});
  }

  /// Toggles flash configuration owned by the platform view identified by [viewId].
  ///
  /// Inactive views retain the requested state until their next camera capture.
  /// Throws a [PlatformException] when the selected camera has no flash and a
  /// [CameraControlException] when the torch operation fails.
  Future<void> toggleFlash({required int viewId}) {
    return _invokeVoidMethod(_toggleFlashMethod, {'viewId': viewId});
  }

  /// Starts recognition requested by the platform view identified by [viewId].
  ///
  /// [type] selects the native recognition mode. [delay] is the minimum
  /// cooldown in milliseconds after successful recognition. On iOS, the same
  /// delay also applies before the first attempt. Failed recognition does not
  /// restart this timer. On Android, failed attempts wait one second before
  /// analyzing the next available camera frame.
  /// Only the current scanner view receives native scan events.
  /// Inactive views retain the request without changing the active scanner.
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
    return _invokeVoidMethod(_startScanMethod, args);
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
    return _invokeVoidMethod(_cancelScanMethod, {'viewId': viewId});
  }

  /// Sets the successful-recognition cooldown owned by [viewId].
  ///
  /// Failed recognition does not start this timer. Inactive views retain the
  /// value without changing the active scanner.
  Future<void> setScanDelay(int delay, {required int viewId}) {
    return _invokeVoidMethod(_setScanDelayMethod, {
      'viewId': viewId,
      'delay': delay,
    });
  }

  /// Pauses camera work requested by [viewId] without releasing ownership.
  ///
  /// Other registered scanner views keep their independent lifecycle intent.
  Future<void> pauseCamera({required int viewId}) {
    return _invokeVoidMethod(_pauseCameraMethod, {'viewId': viewId});
  }

  /// Resumes camera work requested by [viewId].
  ///
  /// The view's retained camera configuration is restored. Detection also
  /// resumes if [startScan] was previously requested by this view.
  /// Can throw [PlatformException] if camera is not initialized.
  Future<void> resumeCamera({required int viewId}) {
    return _invokeVoidMethod(_resumeCameraMethod, {'viewId': viewId});
  }

  /// Sets normalized zoom owned by [viewId].
  ///
  /// [value] must be in the inclusive range from `0` to `1`.
  /// Inactive views retain the value until their next camera capture.
  /// Throws [CameraControlException] when the zoom operation fails.
  Future<void> setZoom(double value, {required int viewId}) {
    return _invokeVoidMethod(_setZoomMethod, {
      'viewId': viewId,
      'value': value,
    });
  }

  /// Sets the recognition area owned by the platform view identified by [viewId].
  ///
  /// [rect] is normalized relative to the camera preview. Inactive views retain
  /// the area without changing the active scanner.
  Future<void> setCropArea(CropRect rect, {required int viewId}) {
    return _invokeVoidMethod(_setCropAreaMethod, {
      'viewId': viewId,
      'cropRect': rect.toJson(),
    });
  }

  /// Returns all iOS cameras supported by the native implementation.
  Future<List<IosCamera>> getIosAvailableCameras() async {
    final availableCameras =
        (await _channel.invokeListMethod<dynamic>(_getIosAvailableCameras))!;
    return availableCameras
        .map((json) => IosCamera.fromJson(Map<String, dynamic>.from(json)))
        .toList();
  }

  /// Selects the iOS camera with [position] and [type] for [viewId].
  ///
  /// Inactive views retain the selection until their next camera capture.
  Future<void> setIosCamera({
    required int viewId,
    required IosCameraPosition position,
    required IosCameraType type,
  }) {
    return _invokeVoidMethod(_setIosCamera, {
      'viewId': viewId,
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
