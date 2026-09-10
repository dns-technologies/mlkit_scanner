import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/scheduler.dart';
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

  /// Current capture, including while waiting for release; cleared before queue cancellation.
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

  /// Selects the latest request immediately, but waits for release before native capture.
  /// Settings supplied while waiting are folded into the capture snapshot.
  Future<void> capture(BarcodeScannerController controller) async {
    if (!_subscriptions.containsKey(controller)) return;

    final session = _ScannerSession(controller, onError: _reportConfigurationError);
    final previous = _session?.controller;

    try {
      if (!controller.configuration.cameraPaused) controller.setPreviewVisible(false);

      final session = _ScannerSession(controller, onError: _reportConfigurationError);
      _session = session;

      if (previous != null) {
        await release(previous);
      }

      if (!identical(_session, session)) return;
      if (!_subscriptions.containsKey(controller) || controller.configuration.cameraPaused) {
        _session = null;
        session.queue.cancel();
        return;
      }

      final configuration = controller.configuration;
      await _channel.resumeCamera(viewId: controller.viewId, configuration: configuration);
      if (!identical(_session, session)) return;
      session.applied = configuration;
      await session.queue.captureComplete();

      // Keep the cover through a frame after zoom and queued controls finish.
      await SchedulerBinding.instance.endOfFrame;
      if (identical(_session, session)) controller.setPreviewVisible(true);
    } catch (_) {
      session.queue.cancel();
      if (identical(_session, session)) _session = null;
      rethrow;
    }
  }

  Future<void> release(BarcodeScannerController controller) async {
    final session = _session;
    if (session == null || identical(session.controller, controller)) {
      _session = null;
      // Publish one barrier before listeners can reenter, even when no camera needs stopping.
      _releasing ??= Future.microtask(() async {
        try {
          if (session != null) await _channel.pauseCamera();
        } finally {
          _releasing = null;
        }
      });
      session?.queue.cancel();
    }
    final releasing = _releasing;
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
      await release(controller);
      return;
    }
    // Waiting captures will read these settings in their complete snapshot.
    if (_releasing != null) return;

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
