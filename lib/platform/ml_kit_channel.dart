import 'dart:async';

import 'package:flutter/services.dart';
import 'package:mlkit_scanner/platform/scanner_configuration.dart';

import '../exceptions/camera_control_exception.dart';
import '../models/barcode.dart';
import '../models/crop_rect.dart';
import '../models/ios_camera.dart';
import 'scanner_preview.dart';

/// A native event addressed to its source preview.
typedef ScannerEvent<T> =
    ({
      /// Logical consumer identifier associated with the native operation.
      int viewId,

      /// Native camera lease used to reject events after ownership changes.
      String? captureId,

      /// Recognition endpoint used to reject results after cancel or restart.
      String? subscriptionId,

      /// Decoded barcode or torch value.
      T value,
    });

/// A preview endpoint's metadata snapshot, including output withdrawal as null.
typedef PreviewEvent =
    ({
      /// Endpoint that owns this preview metadata event or initial snapshot.
      String subscriptionId,

      /// Registered output metadata; null withdraws a previously available texture.
      ScannerPreviewDescription? description,
    });

/// Typed access to the scanner platform channel.
final class MlKitChannel {
  factory MlKitChannel() => _instance ??= MlKitChannel._();

  MlKitChannel._() {
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'onPreviewState') {
        try {
          _previews.add(_decodePreview(call.arguments));
        } on Object {
          // Malformed native events cannot mutate preview state.
        }
      } else if (call.method == 'onScanResult') {
        final event = _decodeClientEvent(call.arguments, 'barcode', (value) => Barcode.fromJson(Map<String, dynamic>.from(value as Map)));
        if (event != null) _scanResultStreamController.add(event);
      } else if (call.method == 'changeTorchStateMethod') {
        final event = _decodeClientEvent(call.arguments, 'value', (value) => value as bool);
        if (event != null) _torchToggleStreamController.add(event);
      }
    });
  }

  /// Shared transport instance with a single native callback handler.
  static MlKitChannel? _instance;

  /// Plugin transport for metadata and commands; video stays native.
  final MethodChannel _channel = const MethodChannel('mlkit_channel');

  /// Broadcasts decoded results with their lease and subscription handles.
  final StreamController<ScannerEvent<Barcode>> _scanResultStreamController = StreamController<ScannerEvent<Barcode>>.broadcast();

  /// Broadcasts iOS torch changes with their originating capture handle.
  final StreamController<ScannerEvent<bool>> _torchToggleStreamController = StreamController<ScannerEvent<bool>>.broadcast();

  /// Synchronous preview metadata notifications.
  final _previews = StreamController<PreviewEvent>.broadcast(sync: true);

  /// Preview metadata changes for all live native output subscriptions.
  Stream<PreviewEvent> get previewEvents => _previews.stream;

  /// All recognized barcodes, tagged with the native source view.
  Stream<ScannerEvent<Barcode>> get scanResults => _scanResultStreamController.stream;

  /// All iOS torch changes, tagged with the native source view.
  Stream<ScannerEvent<bool>> get torchToggleStream => _torchToggleStreamController.stream;

  /// Decodes the common payload of preview callbacks and subscription replies.
  PreviewEvent _decodePreview(Object? arguments) {
    final values = arguments as Map;
    final description = values['description'];
    return (
      subscriptionId: values['subscriptionId'] as String,
      description: description == null ? null : ScannerPreviewDescription.fromJson(description as Map),
    );
  }

  /// Decodes a view-scoped native event and ignores malformed payloads.
  ScannerEvent<T>? _decodeClientEvent<T>(Object? arguments, String valueKey, T Function(Object? value) decodeValue) {
    if (arguments is! Map) return null;
    final viewId = arguments['viewId'];
    if (viewId is! int || !arguments.containsKey(valueKey)) return null;

    try {
      return (
        viewId: viewId,
        captureId: arguments['captureId'] as String?,
        subscriptionId: arguments['subscriptionId'] as String?,
        value: decodeValue(arguments[valueKey]),
      );
    } on Object {
      return null;
    }
  }

  /// Preserves typed camera-control failures when a native command fails.
  Future<void> _invokeVoidMethod(String method, Object? arguments) async {
    try {
      await _channel.invokeMethod<void>(method, arguments);
    } on PlatformException catch (error, stackTrace) {
      if (error.code == CameraControlException.errorCode) {
        Error.throwWithStackTrace(CameraControlException.fromPlatformException(error), stackTrace);
      }
      rethrow;
    }
  }

  /// Requests a physical camera stop for the specified capture lease.
  Future<void> stopCamera({required String captureId}) => _invokeVoidMethod('pauseCameraMethod', {'captureId': captureId});

  /// Starts or resumes the camera and applies settings for the native capture lease.
  /// Completes after SDK configuration; [stopCamera] may interrupt this operation.
  Future<void> resumeCamera({required ScannerConfiguration configuration, required String captureId, required Size geometry}) =>
      _invokeVoidMethod('resumeCameraMethod', {
        'configuration': configuration.toCaptureArguments(),
        'captureId': captureId,
        'geometry': {'width': geometry.width, 'height': geometry.height},
      });

  /// Applies changed controls in one call; omitted fields retain native values.
  /// Completes after all requested controls finish, without restarting preview.
  Future<void> updateCameraSettings({required String captureId, double? zoomRatio, bool? torchEnabled, CropRect? cropRect}) =>
      _invokeVoidMethod('updateCameraSettings', {
        'captureId': captureId,
        if (zoomRatio != null) 'zoomRatio': zoomRatio,
        if (torchEnabled != null) 'torchEnabled': torchEnabled,
        if (cropRect != null) 'cropRect': cropRect.toJson(),
      });

  /// Updates the recognition cooldown.
  Future<void> setScanDelay(int delay, {required String captureId}) =>
      _invokeVoidMethod('setScanDelay', {'delay': delay, 'captureId': captureId});

  /// Starts barcode recognition on the selected preview.
  Future<void> startScan(int delay, {required String captureId, required String subscriptionId}) =>
      _invokeVoidMethod('startScan', {'type': 0, 'delay': delay, 'captureId': captureId, 'subscriptionId': subscriptionId});

  /// Stops recognition without stopping preview.
  Future<void> cancelScan({required String captureId}) => _invokeVoidMethod('cancelScan', {'captureId': captureId});

  /// Registers a logical consumer without allocating a camera or texture.
  Future<void> registerScanner(int viewId) => _invokeVoidMethod('registerScanner', {'viewId': viewId});

  /// Unregisters a logical consumer and closes its active capture lease.
  Future<void> unregisterScanner(int viewId) => _invokeVoidMethod('unregisterScanner', {'viewId': viewId});

  /// Acquires exclusive camera ownership without starting the camera.
  Future<String> openCapture(int viewId) async => (await _channel.invokeMethod<String>('openCapture', {'viewId': viewId}))!;

  /// Revokes a native lease while keeping the shared camera output warm.
  Future<void> closeCapture(String id) => _invokeVoidMethod('closeCapture', {'captureId': id});

  /// Completes after native camera, analyzer and texture resources are released.
  Future<void> disposeScanner() => _invokeVoidMethod('disposeScanner', null);

  /// Subscribes to preview changes and returns the current metadata.
  /// A failed subscription leaves no active endpoint.
  Future<PreviewEvent> subscribePreview() async {
    final reply = await _channel.invokeMethod<Map>('subscribePreview');
    try {
      return _decodePreview(reply);
    } catch (_) {
      final subscriptionId = reply?['subscriptionId'];
      if (subscriptionId is String) await unsubscribePreview(subscriptionId);
      rethrow;
    }
  }

  /// Removes only the specified preview event endpoint.
  Future<void> unsubscribePreview(String id) => _invokeVoidMethod('unsubscribePreview', {'subscriptionId': id});

  /// Allocates a disabled recognition endpoint for the selected lease.
  Future<String> subscribeScan(String id) async => (await _channel.invokeMethod<String>('subscribeScan', {'captureId': id}))!;

  /// Updates viewport mapping without reallocating the shared texture.
  Future<void> updatePreviewGeometry(String id, Size size) =>
      _invokeVoidMethod('updatePreviewGeometry', {'captureId': id, 'width': size.width, 'height': size.height});

  /// Requests continuous or locked focus at the current crop center.
  Future<void> focus(String id, bool locked) => _invokeVoidMethod('focus', {'captureId': id, 'locked': locked});

  /// Returns all iOS cameras supported by the native implementation.
  Future<List<IosCamera>> getIosAvailableCameras() async {
    final availableCameras = (await _channel.invokeListMethod<dynamic>('getIosAvailableCameras'))!;
    return availableCameras.map((json) => IosCamera.fromJson(Map<String, dynamic>.from(json))).toList();
  }
}
