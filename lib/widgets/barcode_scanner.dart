import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:mlkit_scanner/exceptions/scanner_exception.dart';
import 'package:mlkit_scanner/models/barcode.dart';
import 'package:mlkit_scanner/models/crop_rect.dart';
import 'package:mlkit_scanner/models/ios_camera.dart';
import 'package:mlkit_scanner/platform/scanner_configuration.dart';
import 'package:mlkit_scanner/src/platform/scanner_controller.dart';
import 'package:mlkit_scanner/src/widgets/frozen_preview.dart';
import 'package:mlkit_scanner/widgets/camera_preview.dart';

import 'scanner_overlay.dart';

/// Displays a native camera preview and recognizes barcodes.
class BarcodeScanner extends StatefulWidget {
  const BarcodeScanner({
    required this.onScan,
    this.sessionId,
    this.zoomRatio = 1,
    this.flashEnabled = false,
    this.cropRect,
    this.camera,
    this.cameraPaused = false,
    this.scanning = false,
    this.scanDelay = 0,
    this.onError,
    this.onChangeFlashState,
    super.key,
  });

  /// Called for each barcode recognized while scanning is active.
  final ValueChanged<Barcode> onScan;

  /// Identifies this scanner's session using equality (`==`).
  ///
  /// Changing this value recreates the session with the current settings.
  /// Keep it stable across ordinary rebuilds; change it to retry after an error.
  final Object? sessionId;

  /// Receives configuration, initialization and camera operation failures.
  ///
  /// Native failures use the specific [ScannerException] subtype. The
  /// callback may rebuild or replace this scanner; change [sessionId] to restart it.
  /// Without a callback, Flutter reports the error.
  /// Errors from superseded captures and disposed widgets are not delivered.
  final ValueChanged<Object>? onError;

  /// Called when the native torch state changes.
  ///
  /// This callback is currently supported only on iOS.
  final ValueChanged<bool>? onChangeFlashState;

  /// Positive absolute zoom ratio, applied initially and whenever it changes.
  final double zoomRatio;

  /// Desired torch state, applied whenever this scanner owns the capture.
  final bool flashEnabled;

  /// Normalized recognition area; null restores the full preview.
  final CropRect? cropRect;

  /// Physical iOS camera; null restores the platform default.
  /// A non-null value on other platforms is reported through [onError].
  final IosCamera? camera;

  /// Freezes preview and stops recognition. The camera stays warm only for the
  /// configured shutdown grace period, then releases resources while keeping the image.
  /// Resuming after expiry starts the camera with the latest retained settings.
  /// Backgrounding the app stops hardware immediately.
  final bool cameraPaused;

  /// Enables recognition while the camera is active and its route is current.
  /// Modal routes temporarily suspend recognition while retaining the preview.
  final bool scanning;

  /// Successful-recognition cooldown in milliseconds, from 0 to 2147483647.
  final int scanDelay;

  /// Full desired state, allowing null camera and crop to reset previous values.
  ScannerConfiguration get _configuration => ScannerConfiguration(
    zoomRatio: zoomRatio,
    torchEnabled: flashEnabled,
    cropRect: cropRect,
    iosCamera: camera,
    cameraPaused: cameraPaused,
    scanEnabled: scanning,
    scanDelay: scanDelay,
  );

  @override
  State<BarcodeScanner> createState() => _BarcodeScannerState();
}

/// Coordinates scanner configuration, visibility, preview and focus gestures.
class _BarcodeScannerState extends State<BarcodeScanner> with WidgetsBindingObserver {
  /// Allocates real widget identifiers, independently of camera lifetimes.
  static int _nextViewId = 0;

  /// Retained settings and guarded native callbacks for this widget.
  late BarcodeScannerController _controller;

  /// Retained preview whose rasterization must finish before camera handoff.
  late GlobalKey<FrozenPreviewState> _previewKey;

  /// Whether ticker and application visibility permit retaining camera work.
  bool _active = false;

  /// Allows recognition and new ownership only on the foremost route.
  /// The previous owner may restore preview beneath a popup after app resume.
  bool _routeCurrent = true;

  /// Prevents capture until at least one complete valid snapshot is accepted.
  bool _validConfiguration = false;

  /// Whether this visible route owns the capture needed for focus gestures.
  bool get _canFocus => _active && _routeCurrent && _controller.isCurrent;

  /// Whether visibility and valid settings permit acquiring a new capture.
  /// A covered route may restore only its own idle camera beneath a popup.
  bool get _canRequestCapture =>
      mounted && _active && (_routeCurrent || _controller.canRestoreCapture) && _validConfiguration && !_controller.isCurrent;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _startSession();
  }

  /// Starts an independent scanner lifetime with the current desired settings.
  void _startSession() {
    _validConfiguration = false;
    _previewKey = GlobalKey<FrozenPreviewState>();
    late final BarcodeScannerController controller;
    controller = BarcodeScannerController(
      viewId: _nextViewId++,
      onScan: (value) => widget.onScan(value),
      onTorchChanged: (value) => widget.onChangeFlashState?.call(value),
      onError: (error, stack) => _report(controller, error, stack),
      retainPreview: _retainPreview,
    );
    _controller = controller;
    controller.setForeground(_routeCurrent);
    _applyConfiguration();
    controller.captureState.addListener(_updateCaptureState);
    unawaited(_initialize(controller));
  }

  /// Releases capture ownership and stops observing the outgoing session.
  void _disposeController() {
    _controller.captureState.removeListener(_updateCaptureState);
    _controller.dispose();
  }

  /// Applies valid widget settings, reporting invalid settings through [BarcodeScanner.onError].
  void _applyConfiguration() {
    try {
      _controller.applyConfiguration(widget._configuration);
      _validConfiguration = true;
    } catch (error, stack) {
      _controller.reportError(error, stack);
    }
  }

  @override
  void didUpdateWidget(covariant BarcodeScanner oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.sessionId != widget.sessionId) {
      _disposeController();
      _startSession();
      return;
    }
    if (widget._configuration.hasSameSettings(oldWidget._configuration)) return;
    _applyConfiguration();
    unawaited(_capture());
  }

  /// Rebuilds retained preview and focus controls when capture ownership changes.
  void _updateCaptureState() {
    // A different scanner can take ownership while its route is being built.
    // Update this route's feedback after that build, without delaying capture.
    if (WidgetsBinding.instance.schedulerPhase == SchedulerPhase.persistentCallbacks) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) setState(() {});
      });
    } else if (mounted) {
      setState(() {});
    }
  }

  /// Observes registration and starts capture once this widget can own the camera.
  Future<void> _initialize(BarcodeScannerController controller) async {
    try {
      await controller.initialize();
      if (!mounted || controller.isDisposed) return;
      await _capture();
    } catch (error, stack) {
      controller.reportError(error, stack);
    }
  }

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final size = constraints.biggest;
      final controller = _controller;
      final configuration = controller.configuration;
      final cameraActive = _active && !configuration.cameraPaused;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) controller.updateGeometry(size);
      });

      return Stack(
        fit: StackFit.expand,
        children: [
          ValueListenableBuilder(
            valueListenable: controller.preview,
            builder:
                (context, preview, _) => CameraPreview(
                  // Startup frames may arrive before the requested zoom is applied.
                  description:
                      configuration.zoomRatio != 1 && controller.captureState.value == ScannerCaptureState.starting ? null : preview,
                  frameKey: _previewKey,
                  canRetainFrame: () => controller.isCurrent,
                  paused: configuration.cameraPaused || controller.captureState.value == ScannerCaptureState.released,
                  onError: controller.reportError,
                ),
          ),
          ScannerOverlay(
            crop: configuration.cropRect ?? const CropRect(),
            scanning: cameraActive && configuration.scanEnabled,
            focusEnabled: controller.captureState.value == ScannerCaptureState.ready && _canFocus,
            onFocus: () => _focus(false),
            onLockFocus: () => _focus(true),
          ),
        ],
      );
    },
  );

  /// Saves this widget's painted pixels before another capture changes the stream.
  Future<void> _retainPreview() async => _previewKey.currentState?.retainFrame();

  /// Forwards a gesture only if this visible widget still owns the camera.
  void _focus(bool locked) {
    if (!_canFocus) return;
    final controller = _controller;
    unawaited(controller.focus(locked: locked).catchError(controller.reportError));
  }

  /// Acquires camera ownership and reports startup errors.
  Future<void> _capture() async {
    if (!_canRequestCapture) return;
    final controller = _controller;
    try {
      await controller.capture();
    } catch (error, stack) {
      controller.reportError(error, stack);
    }
  }

  /// Delivers errors outside build, using the latest callback of a live widget.
  void _report(BarcodeScannerController controller, Object error, StackTrace stack) {
    void deliver() {
      if (!mounted || controller.isDisposed) return;
      if (widget.onError case final callback?) {
        callback(error);
      } else {
        FlutterError.reportError(FlutterErrorDetails(exception: error, stack: stack, library: 'mlkit_scanner'));
      }
    }

    if (WidgetsBinding.instance.schedulerPhase == SchedulerPhase.persistentCallbacks) {
      WidgetsBinding.instance.addPostFrameCallback((_) => deliver());
    } else {
      scheduleMicrotask(deliver);
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _syncVisibility();
  }

  @override
  void activate() {
    super.activate();
    _syncVisibility();
  }

  @override
  void deactivate() {
    _active = false;
    super.deactivate();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _syncVisibility(deferRelease: false);
    setState(() {});
  }

  /// Keeps preview beneath popups, suspending only recognition until uncovered.
  /// Hidden pages enter the grace period; background apps stop hardware immediately.
  void _syncVisibility({bool deferRelease = true}) {
    final lifecycle = WidgetsBinding.instance.lifecycleState;
    final route = ModalRoute.of(context);
    final routeCurrent = route?.isCurrent ?? true;
    // Keep compatibility with Flutter 3.29; valuesOf was introduced later.
    final active =
        // ignore: deprecated_member_use
        TickerMode.of(context) && !{AppLifecycleState.paused, AppLifecycleState.hidden, AppLifecycleState.detached}.contains(lifecycle);
    final routeChanged = _routeCurrent != routeCurrent;
    if (_active == active && !routeChanged) return;
    _active = active;
    _routeCurrent = routeCurrent;
    _controller.setForeground(routeCurrent);
    if (active) {
      unawaited(_capture());
    } else if (deferRelease) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _releaseIfHidden());
    } else {
      // A background app may render no further frame to run a deferred release.
      if (_controller.isCurrent) {
        final controller = _controller;
        unawaited(controller.suspend().catchError(controller.reportError));
      }
    }
  }

  /// Releases hidden ownership without stopping the stream during the grace period.
  void _releaseIfHidden() {
    if (!mounted || _active || !_controller.isCurrent) return;
    final controller = _controller;
    unawaited(controller.release().catchError(controller.reportError));
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _disposeController();
    super.dispose();
  }
}
