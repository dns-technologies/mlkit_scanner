import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/platform/ml_kit_channel.dart';
import 'package:mlkit_scanner/platform/scanner_configuration.dart';
import 'package:mlkit_scanner/platform/scanner_controller.dart';
import 'package:mlkit_scanner/platform/scanner_runtime.dart';

/// Shared platform-channel fixture for scanner tests.
class RuntimeHarness {
  final calls = <MethodCall>[];
  final errors = <FlutterErrorDetails>[];
  final _previousErrorHandler = FlutterError.onError;
  final controllers = <BarcodeScannerController>[];
  Future<Object?> Function(MethodCall)? handler;
  final runtime = ScannerRuntime(MlKitChannel());
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  static const channel = MethodChannel('mlkit_channel');

  RuntimeHarness() {
    FlutterError.onError = errors.add;
    ScannerRuntime.instance = runtime;
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return handler?.call(call);
    });
  }

  BarcodeScannerController controller(int viewId, {bool scanning = false}) {
    late BarcodeScannerController controller;
    controller = BarcodeScannerController(
        viewId: viewId,
        configuration: ScannerConfiguration(scanEnabled: scanning));
    controllers.add(controller);
    return controller;
  }

  Iterable<String> get methods => calls.map((call) => call.method);
  Future<void> event(int viewId, String value) async {
    await send(MethodCall('onScanResult', {
      'viewId': viewId,
      'barcode': {'raw_value': value, 'format': 256, 'value_type': 7},
    }));
    await send(MethodCall(
        'changeTorchStateMethod', {'viewId': viewId, 'value': true}));
  }

  Future<void> send(MethodCall call) async {
    final reply = Completer<void>();
    messenger.handlePlatformMessage(
        'mlkit_channel',
        const StandardMethodCodec().encodeMethodCall(call),
        (_) => reply.complete());
    await reply.future;
    await flush();
  }

  Future<void> dispose() async {
    handler = null;
    for (final controller in controllers) {
      await runtime.unregister(controller);
      controller.dispose();
    }
    messenger.setMockMethodCallHandler(channel, null);
    FlutterError.onError = _previousErrorHandler;
  }

  static Future<void> flush() => Future<void>.delayed(Duration.zero);
}
