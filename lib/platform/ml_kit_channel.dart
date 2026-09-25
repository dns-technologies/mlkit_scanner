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
      /// Logical widget that originally owned the native operation.
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
class MlKitChannel {
  /// Single native callback handler shared by scanner runtimes.
  static MlKitChannel? _instance;

  /// Plugin transport for metadata and commands; video stays native.
  final MethodChannel _channel = const MethodChannel('mlkit_channel');

  /// Broadcasts decoded results with their lease and subscription handles.
  final StreamController<ScannerEvent<Barcode>> _scanResultStreamController = StreamController<ScannerEvent<Barcode>>.broadcast();

  /// Broadcasts iOS torch changes with their originating capture handle.
  final StreamController<ScannerEvent<bool>> _torchToggleStreamController = StreamController<ScannerEvent<bool>>.broadcast();

  /// Synchronous delivery lets resources buffer events arriving before replies.
  final _previews = StreamController<PreviewEvent>.broadcast(sync: true);

  /// Preview metadata changes for all live native output subscriptions.
  Stream<PreviewEvent> get previewEvents => _previews.stream;

  /// Returns the shared channel instance used by all scanner widgets.
  factory MlKitChannel() => _instance ??= MlKitChannel._();

  /// Creates the shared channel and registers callbacks from native platforms.
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

  /// Invokes a native command and maps camera error code 9 to its typed form.
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

  /// Physically stops camera work when releasing a hidden or background owner.
  /// Manual widget pause keeps hardware running and never calls this method.
  Future<void> stopCamera({required String captureId}) => _invokeVoidMethod('pauseCameraMethod', {'captureId': captureId});

  /// Applies retained settings to the owner identified by the native capture lease.
  /// Used for first startup, ownership changes and recovery after a physical stop.
  /// Completes after SDK configuration; [stopCamera] may interrupt this operation.
  Future<void> activateCapture({required ScannerConfiguration configuration, required String captureId, required Size geometry}) =>
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

  /// Registers a logical widget without allocating a camera or texture.
  Future<void> registerScanner(int viewId) => _invokeVoidMethod('registerScanner', {'viewId': viewId});

  /// Removes a widget and closes its lease if it still owns capture.
  Future<void> unregisterScanner(int viewId) => _invokeVoidMethod('unregisterScanner', {'viewId': viewId});

  /// Allocates a native lease before permission or camera startup can block.
  Future<String> openCapture(int viewId) async => (await _channel.invokeMethod<String>('openCapture', {'viewId': viewId}))!;

  /// Revokes a native lease while keeping the shared camera output warm.
  Future<void> closeCapture(String id) => _invokeVoidMethod('closeCapture', {'captureId': id});

  /// Completes after native camera, analyzer and texture resources are released.
  Future<void> disposeScanner() => _invokeVoidMethod('disposeScanner', null);

  /// Creates a preview endpoint and decodes its initial metadata snapshot.
  /// Releases the endpoint if decoding fails before its owner receives the handle.
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
