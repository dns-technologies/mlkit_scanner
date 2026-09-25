import 'dart:async';
import 'dart:ui';

import 'package:flutter/foundation.dart';
import 'package:mlkit_scanner/src/platform/scanner_controller.dart';

import 'ml_kit_channel.dart';
import 'scanner_capture.dart';
import 'scanner_preview.dart';
import 'scanner_resources.dart';

/// Widget demand and exclusive camera ownership, independent of the shared texture.
class ScannerRuntime {
  /// Shared runtime; the channel factory supplies the instance stored in [_channel].
  static ScannerRuntime _instance = ScannerRuntime(MlKitChannel());

  /// Provides the shared widget registry and native camera lifetime.
  static ScannerRuntime get instance => _instance;

  /// Replaces the runtime at the test boundary before mounting consumers.
  @visibleForTesting
  static set instance(ScannerRuntime value) => _instance = value;

  /// Native command and event transport for this runtime.
  final MlKitChannel _channel;

  /// Current shared texture metadata; null means no output is available.
  final preview = ValueNotifier<ScannerPreviewDescription?>(null);

  /// Live widget registrations, including hidden and manually paused widgets.
  final _consumers = <BarcodeScannerController, ScannerConsumerRegistration>{};

  /// Event listeners forwarding native results to the selected capture.
  final _events = <StreamSubscription>[];

  /// Exclusive camera owner, selected synchronously before activation awaits.
  ScannerCaptureSession? _session;

  /// Actual last owner, allowed to restore preview beneath a popup after resume.
  BarcodeScannerController? _lastOwner;

  /// Preview subscription for the current shared native resource lifetime.
  ScannerResources? _resources;

  /// Cancellable grace period while there are no registered widgets.
  Timer? _idle;

  /// Native resource cleanup that new registrations must await.
  Completer<void>? _disposal;

  /// Hardware pause that must finish before a replacement capture starts.
  Future<void>? _pause;

  /// Pending frame retention, also awaited across successive rapid handoffs.
  Future<void>? _previewRetention;

  /// Owner of the pending pause so repeated release calls await the same work.
  ScannerCaptureSession? _pausingSession;

  /// Cached runtime shutdown shared by concurrent dispose callers.
  Future<void>? _closing;

  /// Rejects new registrations and idle scheduling after shutdown begins.
  bool _disposed = false;

  /// Stores the supplied channel and subscribes to its events without opening the camera.
  ScannerRuntime(this._channel) {
    _events.add(
      _channel.scanResults.listen((event) {
        final session = _session;
        if (session != null && session.accepts(event)) {
          session.controller.addScanResult(event.value);
        }
      }),
    );
    _events.add(
      _channel.torchToggleStream.listen((event) {
        final session = _session;
        if (session != null && session.acceptsTorch(event)) {
          session.controller.addTorchState(event.value);
        }
      }),
    );
  }

  /// Only widgets register demand. A controller alone does not keep the camera alive.
  ScannerConsumerRegistration register(BarcodeScannerController controller) {
    if (_disposed || controller.isDisposed) {
      throw StateError('Scanner is disposed');
    }
    _idle?.cancel();
    _idle = null;
    return _consumers.putIfAbsent(controller, () {
      final consumer = ScannerConsumerRegistration(this, controller);
      consumer.ready = _register(consumer);
      return consumer;
    });
  }

  /// Waits for cleanup, subscribes to preview, then registers a logical widget.
  Future<void> _register(ScannerConsumerRegistration consumer) async {
    await _disposal?.future;
    if (consumer.closed || _disposed) return;
    final resources = _resources ??= ScannerResources(_channel, preview);
    try {
      await resources.ready;
    } catch (_) {
      if (identical(_resources, resources)) _resources = null;
      await resources.close();
      rethrow;
    }
    if (consumer.closed || _disposed) return;
    await _channel.registerScanner(consumer.controller.viewId);
    consumer.nativeRegistered = true;
    if (consumer.closed) {
      await _channel.unregisterScanner(consumer.controller.viewId);
    }
  }

  /// Removes widget demand and schedules cleanup only after the last widget leaves.
  Future<void> unregister(BarcodeScannerController controller) async {
    final consumer = _consumers.remove(controller);
    if (consumer == null) return;
    if (identical(_lastOwner, controller)) _lastOwner = null;
    consumer.closed = true;
    final release = _closeSession(controller, pause: _consumers.isNotEmpty);
    if (_consumers.isEmpty && !_disposed) {
      _idle?.cancel();
      _idle = Timer(const Duration(milliseconds: 300), () {
        _idle = null;
        unawaited(_disposeResources().catchError(_reportError));
      });
    }
    try {
      await release;
    } finally {
      if (consumer.nativeRegistered) {
        await _channel.unregisterScanner(controller.viewId);
      }
    }
  }

  /// Whether this controller owns a capture that still admits commands.
  bool isCurrent(BarcodeScannerController controller) => _session?.controller == controller && _session!.active;

  /// Lets the previous owner restore an idle camera without taking it from a modal.
  bool canRestoreCapture(BarcodeScannerController controller) => _session == null && identical(_lastOwner, controller);

  /// Selects ownership immediately and activates it after registration and pause.
  Future<void> capture(BarcodeScannerController controller) async {
    final consumer = _consumers[controller];
    if (consumer == null || controller.isDisposed) {
      return;
    }
    if (isCurrent(controller)) return;
    final previous = _session;
    // Pause suppresses preview/recognition, not handoff of existing camera demand.
    // A paused widget alone still waits for resume before starting the camera.
    if (controller.configuration.cameraPaused && previous?.active != true) {
      return;
    }
    final retention = Future.wait<void>([
      if (_previewRetention case final pending?) pending,
      if (previous?.controller.retainPreview case final retain?) retain().catchError(previous!.controller.reportError),
    ]).then<void>((_) {});
    _previewRetention = retention;
    unawaited(
      retention.then((_) {
        if (identical(_previewRetention, retention)) _previewRetention = null;
      }),
    );
    late final ScannerCaptureSession session;
    session = ScannerCaptureSession(
      channel: _channel,
      controller: controller,
      geometry: consumer.geometry,
      isSelected: () => identical(_session, session),
      onError: controller.reportError,
    );
    _session = session;
    _lastOwner = controller;
    if (previous != null) {
      unawaited(previous.close().catchError(previous.controller.reportError));
      previous.controller.setPreviewVisible(false);
    }
    try {
      await session.start(
        Future.wait([
          consumer.ready,
          retention,
          // The releasing owner reports pause failures. A new owner only needs
          // to wait until that operation ends, without inheriting its error.
          if (_pause case final pause?) pause.catchError((Object _) {}),
        ]),
      );
    } catch (error, stack) {
      if (identical(_session, session)) {
        _session = null;
        await session.close().catchError(controller.reportError);
        Error.throwWithStackTrace(error, stack);
      }
    }
  }

  /// Relinquishes ownership and stops hardware when this widget becomes hidden.
  Future<void> release(BarcodeScannerController controller) => _closeSession(controller, pause: true);

  /// Revokes ownership and keeps an actual pause barrier for subsequent captures.
  Future<void> _closeSession(BarcodeScannerController controller, {required bool pause}) async {
    final session = _session;
    if (session == null || session.controller != controller) {
      if (_pausingSession?.controller == controller) await _pause;
      return;
    }
    _session = null;
    final closing = session.close(pause: pause);
    if (pause) {
      _pause = closing;
      _pausingSession = session;
    }
    controller.setPreviewVisible(false);
    try {
      await closing;
    } finally {
      if (identical(_pause, closing)) {
        _pause = null;
        _pausingSession = null;
      }
    }
  }

  /// Retains valid layout dimensions and updates only the active native capture.
  void updateGeometry(BarcodeScannerController controller, Size size) {
    if (!size.width.isFinite || !size.height.isFinite || size.isEmpty) return;
    final consumer = _consumers[controller];
    if (consumer == null || consumer.geometry == size) return;
    consumer.geometry = size;
    if (isCurrent(controller)) _session!.update(controller.configuration, size);
  }

  /// Applies a focus gesture only while its controller owns the camera.
  Future<void> focus(BarcodeScannerController controller, {required bool locked}) async {
    if (isCurrent(controller)) await _session!.focus(locked);
  }

  /// Reconciles settings, including warm pause, within the existing capture.
  void configurationChanged(BarcodeScannerController controller) {
    if (!isCurrent(controller)) return;
    _session!.update(controller.configuration, _consumers[controller]!.geometry);
  }

  /// Publishes the disposal barrier before withdrawing preview and native output.
  Future<void> _disposeResources() {
    if (_disposal case final current?) return current.future;
    final operation = Completer<void>();
    _disposal = operation;
    final resources = _resources;
    _resources = null;
    preview.value = null;
    unawaited(
      Future<void>.sync(() async {
        try {
          try {
            await resources?.close();
          } finally {
            await _channel.disposeScanner();
          }
          operation.complete();
        } catch (error, stack) {
          operation.completeError(error, stack);
        } finally {
          if (identical(_disposal, operation)) _disposal = null;
        }
      }),
    );
    return operation.future;
  }

  /// Shuts down this runtime once, awaiting all registered resource cleanup.
  Future<void> dispose() => _closing ??= _dispose();

  /// Attempts every cleanup step and then reports the first failure.
  Future<void> _dispose() async {
    _disposed = true;
    _idle?.cancel();
    _idle = null;
    Object? failure;
    StackTrace? failureStack;
    for (final controller in _consumers.keys.toList()) {
      try {
        await unregister(controller);
      } catch (error, stack) {
        failure ??= error;
        failureStack ??= stack;
      }
    }
    for (final subscription in _events) {
      unawaited(subscription.cancel());
    }
    try {
      await _disposeResources();
    } catch (error, stack) {
      failure ??= error;
      failureStack ??= stack;
    } finally {
      preview.dispose();
    }
    if (failure != null) Error.throwWithStackTrace(failure, failureStack!);
  }

  /// Reports asynchronous updates and cleanup failures through Flutter's handler.
  void _reportError(Object error, StackTrace stack) => FlutterError.reportError(
    FlutterErrorDetails(
      exception: error,
      stack: stack,
      library: 'mlkit_scanner',
      context: ErrorDescription('while applying scanner configuration'),
    ),
  );
}

/// One widget's demand, including while its route is hidden or paused.
class ScannerConsumerRegistration {
  /// Runtime that counts and releases this widget's demand.
  final ScannerRuntime runtime;

  /// Controller retaining this widget's configuration and callbacks.
  final BarcodeScannerController controller;

  /// Native registration completion, also awaited by first capture.
  late final Future<void> ready;

  /// Latest nonempty layout size; the initial value allows pre-layout capture.
  Size geometry = const Size(1, 1);

  /// Prevents late registration work from reviving a removed widget.
  bool closed = false;

  /// Records whether native registration needs a matching unregister call.
  bool nativeRegistered = false;

  /// Records one widget's demand and its owning runtime.
  ScannerConsumerRegistration(this.runtime, this.controller);

  /// Releases this widget's demand without disposing other consumers.
  Future<void> close() => runtime.unregister(controller);
}
