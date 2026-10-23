import 'dart:async';
import 'dart:ui';

import 'package:flutter/foundation.dart';

import 'ml_kit_channel.dart';
import 'scanner_capture.dart';
import 'scanner_consumer.dart';
import 'scanner_preview.dart';
import 'scanner_resources.dart';

/// Exclusive capture ownership with a grace period between active clients.
class ScannerRuntime {
  ScannerRuntime(this._channel) {
    _events.add(
      _channel.scanResults.listen((event) {
        final session = _session;
        if (session != null && session.accepts(event)) {
          session.consumer.addScanResult(event.value);
        }
      }),
    );
    _events.add(
      _channel.torchToggleStream.listen((event) {
        final session = _session;
        if (session != null && session.acceptsTorch(event)) {
          session.consumer.addTorchState(event.value);
        }
      }),
    );
  }

  /// App-wide grace period used when scheduling the next idle shutdown.
  static Duration _cameraShutdownDelay = const Duration(milliseconds: 300);

  /// App-wide owner of shared scanner resources.
  static ScannerRuntime _instance = ScannerRuntime(MlKitChannel());

  /// Native command and event transport for this runtime.
  final MlKitChannel _channel;

  /// Current shared texture metadata; null means no output is available.
  final _preview = ValueNotifier<ScannerPreviewDescription?>(null);

  /// Registered consumers, including those without an active capture.
  final _consumers = <ScannerConsumer, _ScannerRegistration>{};

  /// Event listeners forwarding native results to the selected capture.
  final _events = <StreamSubscription>[];

  /// Exclusive camera owner, selected synchronously before activation awaits.
  ScannerCaptureSession? _session;

  /// Last owner, eligible to restore capture when no other owner is selected.
  ScannerConsumer? _lastOwner;

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

  /// Delay before releasing an idle camera; existing timers keep their deadline.
  static Duration get cameraShutdownDelay => _cameraShutdownDelay;

  /// Updates the shutdown policy without creating a runtime or touching hardware.
  static set cameraShutdownDelay(Duration value) {
    if (value.isNegative) {
      throw ArgumentError.value(value, 'cameraShutdownDelay', 'Must not be negative');
    }
    _cameraShutdownDelay = value;
  }

  /// Provides shared consumer registration and native camera lifetime management.
  static ScannerRuntime get instance => _instance;

  /// Replaces the runtime at the test boundary before mounting consumers.
  @visibleForTesting
  static set instance(ScannerRuntime value) => _instance = value;

  /// Read-only output metadata for consumers of the shared preview.
  ValueListenable<ScannerPreviewDescription?> get preview => _preview;

  /// Registers a consumer without extending the camera lifetime.
  Future<void> register(ScannerConsumer consumer) {
    if (_disposed || consumer.isDisposed) {
      throw StateError('Scanner is disposed');
    }
    return _consumers.putIfAbsent(consumer, () {
      final registration = _ScannerRegistration(consumer);
      registration.ready = _register(registration);
      return registration;
    }).ready;
  }

  /// Registers logical identity; camera resources are acquired by capture only.
  Future<void> _register(_ScannerRegistration registration) async {
    await _disposal?.future;
    if (registration.closed || _disposed) return;
    await _channel.registerScanner(registration.consumer.viewId);
    registration.nativeRegistered = true;
    if (registration.closed) {
      await _channel.unregisterScanner(registration.consumer.viewId);
    }
  }

  /// Unregisters a consumer, releasing capture only if it still owns one.
  Future<void> unregister(ScannerConsumer consumer) async {
    final registration = _consumers.remove(consumer);
    if (registration == null) return;
    if (identical(_lastOwner, consumer)) _lastOwner = null;
    registration.closed = true;
    final release = _closeSession(consumer, pause: false);
    try {
      await release;
    } finally {
      if (registration.nativeRegistered) {
        await _channel.unregisterScanner(consumer.viewId);
      }
    }
  }

  /// Whether this consumer owns a capture that still admits commands.
  bool isCurrent(ScannerConsumer consumer) => _session?.consumer == consumer && _session!.active;

  /// Whether the consumer was the last owner and no capture is currently selected.
  bool canRestoreCapture(ScannerConsumer consumer) => _session == null && identical(_lastOwner, consumer);

  /// Transfers camera ownership to the consumer and prepares its capture.
  Future<void> capture(ScannerConsumer consumer) async {
    final registration = _consumers[consumer];
    if (registration == null || consumer.isDisposed) {
      return;
    }
    if (isCurrent(consumer)) return;
    final previous = _session;
    // Manual pause suppresses preview/recognition, not handoff of an active capture.
    // A paused consumer without an active predecessor does not start the camera.
    if (consumer.configuration.cameraPaused && previous?.active != true) {
      return;
    }
    final retention = _retainPreview(previous?.consumer);
    late final ScannerCaptureSession session;
    session = ScannerCaptureSession(
      channel: _channel,
      consumer: consumer,
      geometry: registration.geometry,
      isSelected: () => identical(_session, session),
      onError: consumer.reportError,
    );
    _session = session;
    _lastOwner = consumer;
    _updateIdleDisposal();
    if (previous != null) {
      unawaited(previous.close().catchError(previous.consumer.reportError));
      previous.consumer.setCaptureState(ScannerCaptureState.released);
    }
    consumer.setCaptureState(ScannerCaptureState.starting);
    try {
      await session.start(_prepareCapture(session, registration, retention));
    } catch (error, stack) {
      if (identical(_session, session)) {
        _session = null;
        consumer.setCaptureState(ScannerCaptureState.released);
        _updateIdleDisposal();
        await session.close().catchError(consumer.reportError);
        Error.throwWithStackTrace(error, stack);
      }
    }
  }

  /// Revokes ownership while leaving the stream warm for the next capture.
  Future<void> release(ScannerConsumer consumer) => _closeSession(consumer, pause: false);

  /// Revokes the consumer's capture and awaits a physical camera stop.
  Future<void> suspend(ScannerConsumer consumer) => _closeSession(consumer, pause: true);

  /// Acquires fresh resources after disposal, including for already registered consumers.
  Future<void> _prepareCapture(ScannerCaptureSession session, _ScannerRegistration registration, Future<void> retention) async {
    await Future.wait([registration.ready, retention, if (_pause case final pause?) pause.catchError((Object _) {})]);
    await _disposal?.future;
    if (!session.active) return;
    if (_resources == null && session.consumer.configuration.cameraPaused) {
      await release(session.consumer);
      return;
    }
    final resources = _resources ??= ScannerResources(_channel, _preview);
    try {
      await resources.ready;
    } catch (_) {
      if (identical(_resources, resources)) _resources = null;
      await resources.close();
      rethrow;
    }
  }

  /// Copies the outgoing owner's pixels before native settings or cleanup change them.
  Future<void> _retainPreview(ScannerConsumer? consumer) {
    final retention = Future.wait<void>([
      if (_previewRetention case final pending?) pending,
      if (consumer != null && !consumer.isDisposed)
        if (consumer.retainPreview case final retain?) Future<void>.sync(retain).catchError(consumer.reportError),
    ]).then<void>((_) {});
    _previewRetention = retention;
    unawaited(
      retention.then((_) {
        if (identical(_previewRetention, retention)) _previewRetention = null;
      }),
    );
    return retention;
  }

  /// Cancels an idle deadline when an unpaused capture needs the camera.
  void _cancelIdleDisposal() {
    _idle?.cancel();
    _idle = null;
  }

  /// Unpaused capture keeps the camera alive; idle settings never extend its deadline.
  void _updateIdleDisposal() {
    final session = _session;
    if (session != null && !session.consumer.configuration.cameraPaused) {
      _cancelIdleDisposal();
      return;
    }
    if (_disposed || _disposal != null || _idle != null) return;
    if (_session == null && _resources == null) return;
    _idle = Timer(cameraShutdownDelay, () {
      _idle = null;
      unawaited(_disposeResources().catchError(_reportError));
    });
  }

  /// Releases the consumer's capture, optionally stopping the camera.
  Future<void> _closeSession(ScannerConsumer consumer, {required bool pause}) async {
    final session = _session;
    if (session == null || session.consumer != consumer) {
      if (_pausingSession?.consumer == consumer) await _pause;
      return;
    }
    _retainPreview(consumer);
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
    consumer.setCaptureState(ScannerCaptureState.released);
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
  void updateGeometry(ScannerConsumer consumer, Size size) {
    if (!size.width.isFinite || !size.height.isFinite || size.isEmpty) return;
    final registration = _consumers[consumer];
    if (registration == null || registration.geometry == size) return;
    registration.geometry = size;
    if (isCurrent(consumer)) _session!.update(consumer.configuration, size);
  }

  /// Applies a focus gesture only while its consumer owns the camera.
  Future<void> focus(ScannerConsumer consumer, {required bool locked}) async {
    if (isCurrent(consumer)) await _session!.focus(locked);
  }

  /// Reconciles settings, including warm pause, within the existing capture.
  void configurationChanged(ScannerConsumer consumer) {
    if (!isCurrent(consumer)) return;
    _session!.update(consumer.configuration, _consumers[consumer]!.geometry);
    _updateIdleDisposal();
  }

  /// Keeps replacement captures waiting until native resource cleanup finishes.
  Future<void> _disposeResources() {
    if (_disposal case final current?) return current.future;
    final operation = Completer<void>();
    _disposal = operation;
    final session = _session;
    final release = session == null ? null : _closeSession(session.consumer, pause: false);
    final resources = _resources;
    _resources = null;
    // Stop metadata delivery before publishing output withdrawal.
    // Native texture disposal still waits for the outgoing image copy.
    final preparation = Future.wait<void>([
      if (release != null) release,
      if (resources != null) resources.close(),
      if (_previewRetention case final retention?) retention,
      if (_pause case final pause?) pause.catchError((Object _) {}),
    ]);
    _preview.value = null;
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
    for (final consumer in _consumers.keys.toList()) {
      try {
        await unregister(consumer);
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
      _preview.dispose();
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

/// Registration status and viewport of one camera consumer.
class _ScannerRegistration {
  _ScannerRegistration(this.consumer);

  /// Registered consumer providing configuration and callbacks.
  final ScannerConsumer consumer;

  /// Completion of native registration.
  late final Future<void> ready;

  /// Latest nonempty layout size; the initial value allows pre-layout capture.
  Size geometry = const Size(1, 1);

  /// Prevents late registration work from reviving a removed consumer.
  bool closed = false;

  /// Records whether native registration needs a matching unregister call.
  bool nativeRegistered = false;
}
