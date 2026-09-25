import 'dart:async';
import 'dart:ui';

import 'package:mlkit_scanner/src/platform/scanner_controller.dart';

import '../models/crop_rect.dart';
import 'ml_kit_channel.dart';
import 'scanner_configuration.dart';

/// A real native ownership lease, with serial desired-state reconciliation.
class ScannerCaptureSession {
  /// Transport used by this lease; never owns video buffers.
  final MlKitChannel channel;

  /// Widget controller whose retained settings drive this capture.
  final BarcodeScannerController controller;

  /// Tests whether the runtime still selects this exact session object.
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

  /// Latest controller intent, which can change during an awaited command.
  ScannerConfiguration _desired;

  /// Latest widget dimensions used for recognition and focus mapping.
  Size _geometry;

  /// Geometry acknowledged by the native capture.
  Size? _appliedGeometry;

  /// Allows point updates only after the initial native activation.
  bool _initialized = false;

  /// Shared completion of the currently running reconciliation loop.
  Future<void>? _draining;

  /// Keeps updates arriving between the last comparison and drain completion.
  bool _reconcileRequested = false;

  /// Cached close operation so concurrent callers await the same cleanup.
  Future<void>? _closing;

  /// Latest unapplied focus gesture; newer gestures replace pending ones.
  _FocusRequest? _focus;

  /// Creates an unallocated lease using the controller's current settings.
  ScannerCaptureSession({
    required this.channel,
    required this.controller,
    required this.isSelected,
    required this.onError,
    required Size geometry,
  }) : _geometry = geometry,
       _desired = controller.configuration;

  /// Whether this lease still admits commands and events.
  bool get active => !_closed.isCompleted && isSelected() && !controller.isDisposed;

  /// Manual pause suspends recognition without changing its retained intent.
  bool get _scanEnabled => _desired.scanEnabled && !_desired.cameraPaused;

  /// Rejects scan results from closed or replaced native subscriptions.
  bool accepts(ScannerEvent event) => active && _scanEnabled && event.subscriptionId != null && event.subscriptionId == _scanSubscription;

  /// Accepts torch changes only from this selected native lease.
  bool acceptsTorch(ScannerEvent event) => active && event.captureId != null && event.captureId == id;

  /// Waits for activation or immediate ownership cancellation, whichever wins.
  Future<void> start(Future<void> registration) => Future.any([_start(registration), _closed.future]);

  /// Allocates and configures a lease after registration and prior pause finish.
  Future<void> _start(Future<void> registration) async {
    try {
      await registration;
      if (!active) return;
      final lease = await channel.openCapture(controller.viewId);
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
        controller.setPreviewVisible(true);
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
    _focus = _FocusRequest(locked);
    await _reconcile();
  }

  /// Revokes admission once, optionally stopping hardware before closing the lease.
  Future<void> close({bool pause = false}) => _closing ??= _close(pause);

  /// Cancels Dart delivery before awaiting native pause and lease cleanup.
  Future<void> _close(bool pause) async {
    _closed.complete();
    _scanSubscription = null;
    _focus = null;
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
    await channel.activateCapture(configuration: desired, captureId: id!, geometry: geometry);
    if (!active) return;
    _applied = desired.copyWith(scanEnabled: false);
    _appliedGeometry = geometry;
    _scanSubscription = null;
  }

  /// Publishes the drain before starting work so reentrant updates share it.
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
  /// Startup owns initial failures; later failures belong to this live session.
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

  /// Re-read desired state after every acknowledgment; stale closures never accumulate.
  Future<void> _applyChanges() async {
    while (active) {
      // Stop recognition first, then finish copying paused pixels before any
      // camera changes. Re-read ownership and intent after rasterization.
      if (_desired.cameraPaused && _applied?.scanEnabled != true) {
        await controller.retainPreview?.call();
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
      } else if (_focus case final request?) {
        _focus = null;
        await channel.focus(lease, request.locked);
      } else {
        return;
      }
    }
  }
}

/// One pending gesture, kept separate from the retained camera configuration.
class _FocusRequest {
  /// Whether the native focus point should remain locked.
  final bool locked;

  /// Captures the requested focus mode until reconciliation reaches it.
  _FocusRequest(this.locked);
}
