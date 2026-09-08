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

  _ScannerSession? _session;
  Future<void>? _release;

  final _subscriptions = <BarcodeScannerController, StreamSubscription<ScannerConfiguration>>{};

  ScannerRuntime(this._channel) {
    _channel.scanResults.listen((event) {
      final session = _session;
      if (session != null && session.accepts(event.viewId) && session.scanning && session.controller.configuration.scanEnabled) {
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

    Future<void> changed(ScannerConfiguration next) async {
      final session = _session;
      if (session == null || !isCurrent(controller)) return;
      // A queued restart must not admit results from the scan it just cancelled.
      if (!next.scanEnabled) session.scanning = false;
      if (next.cameraPaused) {
        if (session.paused && !session.queue.isClosed) return;
        session.queue.cancel();
        final paused = _ScannerSession(controller);
        _session = paused;
        return paused.queue.add(_pauseCamera, stopOnError: true);
      }
      if (session.queue.isClosed) return capture(controller);
      if (session.paused) {
        session.paused = false;
        return session.queue.add(() async {
          final configuration = controller.configuration;
          session.captureSent = true;
          session.scanning = configuration.scanEnabled;
          await _channel.resumeCamera(viewId: controller.viewId, configuration: configuration);
          session.applied = configuration;
        }, stopOnError: true);
      }
      if (!session.captureSent) return;
      return session.queue.add(() => _configurationChanged(session, next));
    }

    _subscriptions[controller] = controller.states.listen((next) {
      unawaited(changed(next).catchError(_reportConfigurationError));
    });
  }

  Future<void> unregister(BarcodeScannerController controller) {
    unawaited(_subscriptions.remove(controller)?.cancel());
    return release(controller);
  }

  bool isCurrent(BarcodeScannerController controller) => identical(_session?.controller, controller);

  /// Replaces the previous connection synchronously; native capture waits for release.
  /// Settings supplied while waiting are folded into the capture snapshot.
  Future<void> capture(BarcodeScannerController controller) async {
    if (!_subscriptions.containsKey(controller)) return;
    final previous = _session;
    previous?.queue.cancel();
    final session = _ScannerSession(controller);
    final initiallyPaused = session.paused;
    _session = session;

    return session.queue.add(
      () async {
        // Explicit release clears selection before native cancellation is acknowledged.
        // This await also folds synchronous initialization settings into the snapshot.
        await (previous != null ? _pauseCamera() : _release);
        if (session.queue.isClosed) return;
        // A resume queued during this wait owns startup for an initially paused view.
        if (initiallyPaused) return;

        final configuration = controller.configuration;
        session.captureSent = true;
        session.scanning = configuration.scanEnabled;
        await _channel.resumeCamera(viewId: controller.viewId, configuration: configuration);
        session.applied = configuration;
      },
      stopOnError: true,
    );
  }

  Future<void> release(BarcodeScannerController controller) async {
    final session = _session;
    if (session == null || !isCurrent(controller)) return;
    session.queue.cancel();
    _session = null;
    await _pauseCamera();
  }

  Future<void> _pauseCamera() => _release = _channel.pauseCamera();

  Future<void> _configurationChanged(
    _ScannerSession session,
    ScannerConfiguration next,
  ) async {
    final applied = session.applied;
    if (applied == null || applied.iosCamera?.position != next.iosCamera?.position || applied.iosCamera?.type != next.iosCamera?.type) {
      // A partial failure or physical camera change requires a complete snapshot.
      return capture(session.controller).catchError(_reportConfigurationError);
    }

    Future<void> apply(bool changed, Future<void> Function() action) async {
      if (changed && !session.queue.isClosed) await action();
    }

    try {
      await apply(applied.zoomRatio != next.zoomRatio, () => _channel.setZoomRatio(next.zoomRatio));
      await apply(applied.torchEnabled != next.torchEnabled, () => _channel.toggleFlash(next.torchEnabled));
      await apply(!mapEquals(applied.cropRect?.toJson(), next.cropRect?.toJson()), () => _channel.setCropArea(next.cropRect!));
      await apply(
          applied.scanDelay != next.scanDelay && (applied.scanEnabled || !next.scanEnabled), () => _channel.setScanDelay(next.scanDelay));
      await apply(applied.scanEnabled != next.scanEnabled, () {
        session.scanning = next.scanEnabled;
        return next.scanEnabled ? _channel.startScan(next.scanDelay) : _channel.cancelScan();
      });
    } catch (_) {
      session.applied = null;
      rethrow;
    }
    session.applied = next;
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

/// State owned by one capture, discarded together when its controller releases.
class _ScannerSession {
  final BarcodeScannerController controller;
  final queue = CommandQueue();
  bool paused;
  bool captureSent = false;

  /// False immediately on cancellation, true only when native startup is sent.
  bool scanning = false;

  /// The last fully acknowledged snapshot; null until capture succeeds or after a partial failure.
  ScannerConfiguration? applied;

  _ScannerSession(this.controller) : paused = controller.configuration.cameraPaused;

  bool accepts(int viewId) => controller.viewId == viewId && !paused && captureSent && !queue.isClosed;
}
