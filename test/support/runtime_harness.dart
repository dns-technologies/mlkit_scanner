import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/models/barcode.dart';
import 'package:mlkit_scanner/models/crop_rect.dart';
import 'package:mlkit_scanner/platform/ml_kit_channel.dart';
import 'package:mlkit_scanner/platform/scanner_configuration.dart';
import 'package:mlkit_scanner/platform/scanner_runtime.dart';
import 'package:mlkit_scanner/src/platform/scanner_controller.dart';

/// Shared platform-channel fixture for scanner tests.
class RuntimeHarness {
  final calls = <MethodCall>[];
  final errors = <FlutterErrorDetails>[];
  final _previousErrorHandler = FlutterError.onError;
  final controllers = <BarcodeScannerController>[];
  Future<Object?> Function(MethodCall)? handler;
  int _nextLease = 0;
  int _nextSubscription = 0;
  final leases = <int, String>{};
  final scans = <String, String>{};
  bool terminalFailureExpected = false;
  bool _disposed = false;
  final runtime = ScannerRuntime(MlKitChannel());
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  static const channel = MethodChannel('mlkit_channel');

  RuntimeHarness() {
    FlutterError.onError = errors.add;
    ScannerRuntime.instance = runtime;
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      final response = await handler?.call(call);
      if (response != null) return response;
      switch (call.method) {
        case 'openCapture':
          final id = 'capture-${++_nextLease}';
          leases[(call.arguments as Map)['viewId'] as int] = id;
          return id;
        case 'subscribeScan':
          final id = 'scan-${++_nextSubscription}';
          scans[(call.arguments as Map)['captureId'] as String] = id;
          return id;
        case 'subscribePreview':
          return {'subscriptionId': 'preview', 'description': null};
        default:
          return null;
      }
    });
  }

  BarcodeScannerController controller(
    int viewId, {
    bool scanning = false,
    ValueChanged<Barcode>? onScan,
    ValueChanged<bool>? onTorchChanged,
  }) {
    final controller = BarcodeScannerController(
      viewId: viewId,
      configuration: ScannerConfiguration(scanEnabled: scanning),
      onScan: onScan,
      onTorchChanged: onTorchChanged,
    );
    controllers.add(controller);
    runtime.register(controller);
    return controller;
  }

  Iterable<String> get methods => calls.map((call) => call.method);

  /// Simulates the visible widget requesting capture after the controller resumes.
  Future<void> resumeVisible(BarcodeScannerController controller) async {
    await controller.configure(cameraPaused: false);
    if (!runtime.isCurrent(controller)) {
      unawaited(
        runtime.capture(controller).catchError((Object error, StackTrace stack) {
          errors.add(FlutterErrorDetails(exception: error, stack: stack));
        }),
      );
    }
  }

  Future<void> event(int viewId, String value, {String? captureId, String? subscriptionId}) async {
    final lease = captureId ?? leases[viewId];
    await send(
      MethodCall('onScanResult', {
        'viewId': viewId,
        'captureId': lease,
        'subscriptionId': subscriptionId ?? scans[lease],
        'barcode': {'raw_value': value, 'format': 256, 'value_type': 7},
      }),
    );
    await send(MethodCall('changeTorchStateMethod', {'viewId': viewId, 'captureId': lease, 'value': true}));
  }

  Future<void> send(MethodCall call) async {
    final reply = Completer<void>();
    messenger.handlePlatformMessage('mlkit_channel', const StandardMethodCodec().encodeMethodCall(call), (_) => reply.complete());
    await reply.future;
  }

  Future<void> dispose() async {
    if (_disposed) return;
    _disposed = true;
    handler = null;
    for (final controller in controllers) {
      await runtime.unregister(controller);
      controller.dispose();
    }
    try {
      await runtime.dispose();
    } catch (_) {
      if (!terminalFailureExpected) rethrow;
    }
    messenger.setMockMethodCallHandler(channel, null);
    FlutterError.onError = _previousErrorHandler;
  }

  static Future<void> flush() => Future<void>.delayed(Duration.zero);
}

/// Changes test inputs through the same complete-snapshot path as the widget.
extension ScannerTestConfiguration on BarcodeScannerController {
  Future<void> configure({
    double? zoomRatio,
    bool? torchEnabled,
    bool? scanEnabled,
    bool? cameraPaused,
    int? scanDelay,
    CropRect? cropRect,
  }) async => applyConfiguration(
    configuration.copyWith(
      zoomRatio: zoomRatio,
      torchEnabled: torchEnabled,
      scanEnabled: scanEnabled,
      cameraPaused: cameraPaused,
      scanDelay: scanDelay,
      cropRect: cropRect,
    ),
  );
}
