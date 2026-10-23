import 'dart:async';
import 'dart:ui';

import '../models/crop_rect.dart';
import 'ml_kit_channel.dart';
import 'scanner_configuration.dart';
import 'scanner_consumer.dart';

/// Exclusive camera ownership with updates to camera and recognition settings.
class ScannerCaptureSession {
  ScannerCaptureSession({
    required this.channel,
    required this.consumer,
    required this.isSelected,
    required this.onError,
    required Size geometry,
  }) : _geometry = geometry,
       _desired = consumer.configuration;

  /// Transport used by this lease; never owns video buffers.
  final MlKitChannel channel;

  /// Consumer whose retained settings drive this capture.
  final ScannerConsumer consumer;

  /// Checks whether this session is still selected for camera ownership.
  final bool Function() isSelected;

  /// Reports failed background updates while this session is still selected.
  final void Function(Object, StackTrace) onError;

  /// Unblocks startup callers as soon as ownership is revoked.
  final _closed = Completer<void>();

  /// Native lease handle, assigned when allocation is acknowledged.
  String? id;

  /// Only this native result endpoint may deliver scan events.
  String? _scanSubscription;

  /// Last acknowledged settings; scan delay matters only while recognition runs.
  /// Null means a full reapplication is needed.
  ScannerConfiguration? _applied;

  /// Latest consumer intent, which can change during an awaited command.
  ScannerConfiguration _desired;

  /// Latest viewport dimensions used for recognition and focus mapping.
  Size _geometry;

  /// Geometry acknowledged by the native capture.
  Size? _appliedGeometry;

  /// Allows point updates only after the initial native activation.
  bool _initialized = false;

  /// Shared completion of the currently running reconciliation loop.
  Future<void>? _draining;

  /// Whether requested settings still need to be applied.
  bool _reconcileRequested = false;

  /// Cached close operation so concurrent callers await the same cleanup.
  Future<void>? _closing;

  /// Latest unapplied focus gesture; newer gestures replace pending ones.
  bool? _pendingFocusLocked;

  /// Whether this lease still admits commands and events.
  bool get active => !_closed.isCompleted && isSelected() && !consumer.isDisposed;

  /// Manual pause suspends recognition without changing its retained intent.
  bool get _scanEnabled => _desired.scanEnabled && !_desired.cameraPaused;

  /// Rejects scan results from closed or replaced native subscriptions.
  bool accepts(ScannerEvent event) => active && _scanEnabled && event.subscriptionId != null && event.subscriptionId == _scanSubscription;

  /// Accepts torch changes only from this selected native lease.
  bool acceptsTorch(ScannerEvent event) => active && event.captureId != null && event.captureId == id;

  /// Waits for activation or immediate ownership cancellation, whichever wins.
  Future<void> start(Future<void> registration) => Future.any([_start(registration), _closed.future]);

  /// Prepares camera ownership and applies the requested settings.
  Future<void> _start(Future<void> registration) async {
    try {
      await registration;
      if (!active) return;
      final lease = await channel.openCapture(consumer.viewId);
      id = lease;
      if (!active) {
        await channel.closeCapture(lease);
        return;
      }
      await _activate();
      if (!active) return;
      // Startup updates can arrive after a drain completes but before this
      // continuation runs. Consume them before enabling regular reconciliation.
      do {
        await _reconcile();
      } while (active && _reconcileRequested);
      if (active) {
        _initialized = true;
        consumer.setCaptureState(ScannerCaptureState.ready);
      }
    } catch (error, stack) {
      if (active) Error.throwWithStackTrace(error, stack);
    }
  }

  /// Coalesces pending settings and revokes disabled result delivery immediately.
  void update(ScannerConfiguration desired, Size geometry) {
    _desired = desired;
    _geometry = geometry;
    _reconcileRequested = true;
    if (!_scanEnabled) _scanSubscription = null;
    if (!active || !_initialized) return;
    unawaited(_reconcile());
  }

  /// Reconciles the latest focus gesture after outstanding camera settings.
  Future<void> focus(bool locked) async {
    if (!active || !_initialized) return;
    _pendingFocusLocked = locked;
    await _reconcile();
  }

  /// Revokes admission once, optionally stopping hardware before closing the lease.
  Future<void> close({bool pause = false}) => _closing ??= _close(pause);

  /// Releases camera ownership and prevents further result delivery.
  Future<void> _close(bool pause) async {
    _closed.complete();
    _scanSubscription = null;
    _pendingFocusLocked = null;
    final lease = id;
    if (lease == null) return;
    try {
      if (pause) await channel.stopCamera(captureId: lease);
    } finally {
      await channel.closeCapture(lease);
    }
  }

  /// Applies camera settings with recognition disabled until subscribed.
  Future<void> _activate() async {
    final desired = _desired;
    final geometry = _geometry;
    await channel.resumeCamera(configuration: desired, captureId: id!, geometry: geometry);
    if (!active) return;
    _applied = desired.copyWith(scanEnabled: false);
    _appliedGeometry = geometry;
    _scanSubscription = null;
  }

  /// Applies pending settings; concurrent requests share completion.
  Future<void> _reconcile() {
    _reconcileRequested = true;
    final current = _draining;
    if (current != null) return current;
    final done = Completer<void>();
    _draining = done.future;
    unawaited(_drain(done));
    return done.future;
  }

  /// Reports each failed operation once, even when updates and focus share it.
  /// Initial failures complete the startup future; later failures invoke [onError].
  Future<void> _drain(Completer<void> done) async {
    try {
      do {
        _reconcileRequested = false;
        await _applyChanges();
      } while (_reconcileRequested && active);
      done.complete();
    } catch (error, stack) {
      _applied = null;
      if (!_initialized) {
        done.completeError(error, stack);
      } else {
        if (active) onError(error, stack);
        done.complete();
      }
    } finally {
      _draining = null;
    }
  }

  /// Applies the latest camera and recognition settings to the active capture.
  Future<void> _applyChanges() async {
    while (active) {
      // Stop recognition first, then finish copying paused pixels before any
      // camera changes. Re-read ownership and intent after rasterization.
      if (_desired.cameraPaused && _applied?.scanEnabled != true) {
        await consumer.retainPreview?.call();
        if (!active) return;
      }
      final desired = _desired;
      final applied = _applied;
      final lease = id!;
      final scanEnabled = _scanEnabled;
      if (!scanEnabled && applied?.scanEnabled == true) {
        // Pause stops recognition first; camera controls still use this lease.
        await channel.cancelScan(captureId: lease);
        if (active) _applied = applied!.copyWith(scanEnabled: false);
      } else if (applied == null || !applied.hasSameCamera(desired)) {
        await _activate();
      } else if (_appliedGeometry != _geometry) {
        final geometry = _geometry;
        await channel.updatePreviewGeometry(lease, geometry);
        if (active) _appliedGeometry = geometry;
      } else if (!applied.hasSameCameraSettings(desired)) {
        await channel.updateCameraSettings(
          captureId: lease,
          zoomRatio: applied.zoomRatio != desired.zoomRatio ? desired.zoomRatio : null,
          torchEnabled: applied.torchEnabled != desired.torchEnabled ? desired.torchEnabled : null,
          cropRect: !applied.hasSameCrop(desired) ? desired.cropRect ?? const CropRect() : null,
        );
        if (active) {
          _applied = applied.copyWith(
            zoomRatio: desired.zoomRatio,
            torchEnabled: desired.torchEnabled,
            cropRect: desired.cropRect ?? const CropRect(),
          );
        }
      } else if (scanEnabled && (!applied.scanEnabled || _scanSubscription == null)) {
        final subscription = await channel.subscribeScan(lease);
        if (!active) return;
        if (!_scanEnabled) {
          await channel.cancelScan(captureId: lease);
          continue;
        }
        _scanSubscription = subscription;
        final delay = _desired.scanDelay;
        await channel.startScan(delay, captureId: lease, subscriptionId: subscription);
        if (active) _applied = applied.copyWith(scanEnabled: true, scanDelay: delay);
      } else if (scanEnabled && applied.scanDelay != desired.scanDelay) {
        await channel.setScanDelay(desired.scanDelay, captureId: lease);
        if (active) _applied = applied.copyWith(scanDelay: desired.scanDelay);
      } else if (_pendingFocusLocked case final locked?) {
        _pendingFocusLocked = null;
        await channel.focus(lease, locked);
      } else {
        return;
      }
    }
  }
}
