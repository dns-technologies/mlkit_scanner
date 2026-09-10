import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:mlkit_scanner/platform/command_queue.dart';
import 'package:mlkit_scanner/platform/ml_kit_channel.dart';
import 'package:mlkit_scanner/platform/scanner_configuration.dart';
import 'package:mlkit_scanner/platform/scanner_controller.dart';

/// Listens to controller state; only the latest capture can drive the shared native pipeline.
class ScannerRuntime {
  static ScannerRuntime _instance = ScannerRuntime(MlKitChannel());

  static ScannerRuntime get instance => _instance;

  @visibleForTesting
  static set instance(ScannerRuntime value) => _instance = value;

  final MlKitChannel _channel;

  /// Current capture; cleared before its queue is cancelled.
  _ScannerSession? _session;

  /// Shared result of the current release; cleared after success or failure.
  Future<void>? _releasing;

  final _subscriptions = <BarcodeScannerController, StreamSubscription<ScannerConfiguration>>{};

  ScannerRuntime(this._channel) {
    _channel.scanResults.listen((event) {
      final session = _session;
      if (session != null && session.accepts(event.viewId)) {
        session.controller.addScanResult(event.value);
      }
    });
    _channel.torchToggleStream.listen((event) {
      final session = _session;
      if (session != null && session.accepts(event.viewId)) {
        session.controller.addTorchState(event.value);
      }
    });
  }

  /// Registration tracks state changes, not camera ownership.
  void register(BarcodeScannerController controller) {
    if (_subscriptions.containsKey(controller)) return;

    var previous = controller.configuration;
    _subscriptions[controller] = controller.states.listen((next) {
      final before = previous;
      previous = next;
      unawaited(_configurationChanged(controller, before, next).catchError(_reportConfigurationError));
    });
  }

  Future<void> unregister(BarcodeScannerController controller) async {
    unawaited(_subscriptions.remove(controller)?.cancel());
  }

  bool isCurrent(BarcodeScannerController controller) {
    final session = _session;
    return session != null && identical(session.controller, controller);
  }

  /// Releases the previous connection before creating the next session.
  /// Settings supplied while waiting are folded into the capture snapshot.
  Future<void> capture(BarcodeScannerController controller) async {
    if (!_subscriptions.containsKey(controller)) return;
    await release(_session?.controller ?? controller);

    if (!_subscriptions.containsKey(controller) || controller.configuration.cameraPaused) return;
    final session = _ScannerSession(controller, onError: _reportConfigurationError);
    _session = session;

    controller.setPreviewVisible(false);
    try {
      // Fold synchronous initialization settings into the capture snapshot.
      await Future.value();
      if (!identical(_session, session)) return;
      final configuration = controller.configuration;
      await _channel.resumeCamera(viewId: controller.viewId, configuration: configuration);
      if (!identical(_session, session)) return;
      session.applied = configuration;
      await session.queue.captureComplete();

      if (identical(_session, session)) controller.setPreviewVisible(true);
    } catch (_) {
      if (identical(_session, session)) _session = null;
      session.queue.cancel();
    }
  }

  Future<void> release(BarcodeScannerController controller, {bool keepPreview = false}) async {
    final session = _session;
    if (session != null && identical(session.controller, controller)) {
      _session = null;
      // Publish the shared reply before cancellation or preview listeners can reenter release.
      _releasing = Future(() async {
        try {
          await _channel.pauseCamera();
        } finally {
          _releasing = null;
        }
      });
      session.queue.cancel();
    }
    final releasing = _releasing;
    if (!keepPreview) controller.setPreviewVisible(false);
    return releasing;
  }

  Future<void> _configurationChanged(
    BarcodeScannerController controller,
    ScannerConfiguration previous,
    ScannerConfiguration next,
  ) async {
    final session = _session;
    if (session == null || !isCurrent(controller)) return;

    if (next.cameraPaused) {
      await release(controller, keepPreview: true);
      return;
    }

    void enqueue(String id, bool changed, Future<void> Function() callback) {
      if (!changed) return;
      session.queue.add(ScannerCommand(id, () async {
        try {
          await callback();
        } catch (e) {
          session.applied = null;
          rethrow;
        }
      }));
    }

    enqueue('setZoomRatio', previous.zoomRatio != next.zoomRatio, () async {
      await _channel.setZoomRatio(next.zoomRatio);
      session.applied = session.applied?.copyWith(zoomRatio: next.zoomRatio);
    });
    enqueue('toggleFlash', previous.torchEnabled != next.torchEnabled, () async {
      await _channel.toggleFlash(next.torchEnabled);
      session.applied = session.applied?.copyWith(torchEnabled: next.torchEnabled);
    });
    enqueue('setCropArea', !mapEquals(previous.cropRect?.toJson(), next.cropRect?.toJson()), () async {
      await _channel.setCropArea(next.cropRect!);
      session.applied = session.applied?.copyWith(cropRect: next.cropRect);
    });
    enqueue('setScanDelay', previous.scanDelay != next.scanDelay, () async {
      await _channel.setScanDelay(next.scanDelay);
      session.applied = session.applied?.copyWith(scanDelay: next.scanDelay);
    });
    enqueue('scan', previous.scanEnabled != next.scanEnabled, () async {
      await (next.scanEnabled ? _channel.startScan(next.scanDelay) : _channel.cancelScan());
      final applied = session.applied;
      session.applied = applied?.copyWith(scanEnabled: next.scanEnabled, scanDelay: next.scanEnabled ? next.scanDelay : applied.scanDelay);
    });
    enqueue('setIosCamera', previous.iosCamera?.position != next.iosCamera?.position || previous.iosCamera?.type != next.iosCamera?.type,
        () async {
      await capture(session.controller).catchError(_reportConfigurationError);
    });
  }

  void _reportConfigurationError(Object error, StackTrace stack) {
    FlutterError.reportError(FlutterErrorDetails(
      exception: error,
      stack: stack,
      library: 'mlkit_scanner',
      context: ErrorDescription('while applying scanner configuration'),
    ));
  }
}

/// State owned by one capture; runtime discards it before cancellation.
class _ScannerSession {
  final BarcodeScannerController controller;
  final CommandQueue queue;

  /// The last fully acknowledged snapshot; null until capture succeeds or after a partial failure.
  ScannerConfiguration? applied;

  _ScannerSession(this.controller, {required void Function(Object error, StackTrace stack) onError})
      : queue = CommandQueue(onError: onError);

  bool accepts(int viewId) => controller.viewId == viewId && queue.isCaptured;
}
