import 'dart:async';
import 'dart:ui';

import 'package:flutter/foundation.dart';
import 'package:mlkit_scanner/src/platform/scanner_controller.dart';
import 'package:mlkit_scanner/utils/mlkit_utils.dart';

import 'ml_kit_channel.dart';
import 'scanner_capture.dart';
import 'scanner_preview.dart';
import 'scanner_resources.dart';

/// Exclusive capture ownership with a grace period between active clients.
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

  /// Cancellable grace period while capture is absent or manually paused.
  Timer? _idle;

  /// Native resource cleanup that new registrations and captures must await.
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

  /// Registers a logical widget without extending the camera lifetime.
  ScannerConsumerRegistration register(BarcodeScannerController controller) {
    if (_disposed || controller.isDisposed) {
      throw StateError('Scanner is disposed');
    }
    return _consumers.putIfAbsent(controller, () {
      final consumer = ScannerConsumerRegistration(this, controller);
      consumer.ready = _register(consumer);
      return consumer;
    });
  }

  /// Registers logical identity; camera resources are acquired by capture only.
  Future<void> _register(ScannerConsumerRegistration consumer) async {
    await _disposal?.future;
    if (consumer.closed || _disposed) return;
    await _channel.registerScanner(consumer.controller.viewId);
    consumer.nativeRegistered = true;
    if (consumer.closed) {
      await _channel.unregisterScanner(consumer.controller.viewId);
    }
  }

  /// Removes a logical widget, releasing capture only if it still owns one.
  Future<void> unregister(BarcodeScannerController controller) async {
    final consumer = _consumers.remove(controller);
    if (consumer == null) return;
    if (identical(_lastOwner, controller)) _lastOwner = null;
    consumer.closed = true;
    final release = _closeSession(controller, pause: false);
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
    // Manual pause suppresses preview/recognition, not handoff of an active capture.
    // A paused widget alone still waits for resume before starting the camera.
    if (controller.configuration.cameraPaused && previous?.active != true) {
      return;
    }
    final retention = _retainPreview(previous?.controller);
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
    _updateIdleDisposal();
    if (previous != null) {
      unawaited(previous.close().catchError(previous.controller.reportError));
      previous.controller.setCaptureState(ScannerCaptureState.released);
    }
    controller.setCaptureState(ScannerCaptureState.starting);
    try {
      await session.start(_prepareCapture(session, consumer, retention));
    } catch (error, stack) {
      if (identical(_session, session)) {
        _session = null;
        controller.setCaptureState(ScannerCaptureState.released);
        _updateIdleDisposal();
        await session.close().catchError(controller.reportError);
        Error.throwWithStackTrace(error, stack);
      }
    }
  }

  /// Revokes ownership while leaving the stream warm for the next capture.
  Future<void> release(BarcodeScannerController controller) => _closeSession(controller, pause: false);

  /// Stops hardware immediately when the app leaves the foreground.
  Future<void> suspend(BarcodeScannerController controller) => _closeSession(controller, pause: true);

  /// Acquires fresh resources after disposal, including for already registered widgets.
  Future<void> _prepareCapture(ScannerCaptureSession session, ScannerConsumerRegistration consumer, Future<void> retention) async {
    await Future.wait([consumer.ready, retention, if (_pause case final pause?) pause.catchError((Object _) {})]);
    await _disposal?.future;
    if (!session.active) return;
    if (_resources == null && session.controller.configuration.cameraPaused) {
      await release(session.controller);
      return;
    }
    final resources = _resources ??= ScannerResources(_channel, preview);
    try {
      await resources.ready;
    } catch (_) {
      if (identical(_resources, resources)) _resources = null;
      await resources.close();
      rethrow;
    }
  }

  /// Copies the outgoing owner's pixels before native settings or cleanup change them.
  Future<void> _retainPreview(BarcodeScannerController? controller) {
    final retention = Future.wait<void>([
      if (_previewRetention case final pending?) pending,
      if (controller != null && !controller.isDisposed)
        if (controller.retainPreview case final retain?) Future<void>.sync(retain).catchError(controller.reportError),
    ]).then<void>((_) {});
    _previewRetention = retention;
    unawaited(
      retention.then((_) {
        if (identical(_previewRetention, retention)) _previewRetention = null;
      }),
    );
    return retention;
  }

  void _cancelIdleDisposal() {
    _idle?.cancel();
    _idle = null;
  }

  /// Unpaused capture keeps the camera alive; idle settings never extend its deadline.
  void _updateIdleDisposal() {
    final session = _session;
    if (session != null && !session.controller.configuration.cameraPaused) {
      _cancelIdleDisposal();
      return;
    }
    if (_disposed || _disposal != null || _idle != null) return;
    if (_session == null && _resources == null) return;
    _idle = Timer(MLKitUtils.cameraShutdownDelay, () {
      _idle = null;
      unawaited(_disposeResources().catchError(_reportError));
    });
  }

  /// Revokes ownership and keeps an actual pause barrier for subsequent captures.
  Future<void> _closeSession(BarcodeScannerController controller, {required bool pause}) async {
    final session = _session;
    if (session == null || session.controller != controller) {
      if (_pausingSession?.controller == controller) await _pause;
      return;
    }
    _retainPreview(controller);
    _session = null;
    var closing = session.close(pause: pause);
    if (pause) {
      // A capture cancelled before allocation must still wait for the previous
      // owner's physical stop. That owner remains responsible for its error.
      if (_pause case final pending?) {
        closing = Future.wait<void>([pending.catchError((Object _) {}), closing]).then<void>((_) {});
      }
      _pause = closing;
      _pausingSession = session;
    }
    controller.setCaptureState(ScannerCaptureState.released);
    _updateIdleDisposal();
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
    _updateIdleDisposal();
  }

  /// Publishes the disposal barrier before withdrawing preview and native output.
  Future<void> _disposeResources() {
    if (_disposal case final current?) return current.future;
    final operation = Completer<void>();
    _disposal = operation;
    final session = _session;
    final release = session == null ? null : _closeSession(session.controller, pause: false);
    final resources = _resources;
    _resources = null;
    // Stop metadata delivery before notifying widgets that the old output is gone.
    // Native texture disposal still waits for the outgoing image copy.
    final preparation = Future.wait<void>([
      if (release != null) release,
      if (resources != null) resources.close(),
      if (_previewRetention case final retention?) retention,
      if (_pause case final pause?) pause.catchError((Object _) {}),
    ]);
    preview.value = null;
    unawaited(
      Future<void>.sync(() async {
        try {
          try {
            await preparation;
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
    _cancelIdleDisposal();
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

/// One logical widget, independent of the shared native resource lifetime.
class ScannerConsumerRegistration {
  /// Runtime that registers this widget and selects its captures.
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

  ScannerConsumerRegistration(this.runtime, this.controller);

  /// Removes this registration without unregistering other widgets.
  Future<void> close() => runtime.unregister(controller);
}
