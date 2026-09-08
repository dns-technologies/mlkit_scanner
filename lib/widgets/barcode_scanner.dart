import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';
import 'package:mlkit_scanner/platform/scanner_configuration.dart';
import 'package:mlkit_scanner/platform/scanner_runtime.dart';

import 'package:mlkit_scanner/widgets/camera_preview.dart';

export 'package:mlkit_scanner/platform/scanner_controller.dart' show BarcodeScannerController;

/// Displays a native camera preview and recognizes barcodes.
class BarcodeScanner extends StatefulWidget {
  /// Called for each barcode recognized while scanning is active.
  final ValueChanged<Barcode> onScan;

  /// Called once after the native platform view is registered.
  ///
  /// Camera capture may still be pending. Configuration calls made from this
  /// callback or while capture is in progress update this view's retained state
  /// and are applied when the camera becomes ready. Capture failures are reported
  /// independently through [onCameraInitializeError].
  final void Function(BarcodeScannerController controller) onScannerInitialized;

  /// Called for every camera capture or initialization failure.
  ///
  /// The controller has already been delivered through [onScannerInitialized]
  /// when an initial capture fails and remains valid for retained configuration
  /// updates or a later capture. Capture-time retained-control failures arrive
  /// here as [CameraControlException]. Controller setters publish desired state;
  /// asynchronous configuration failures are reported through FlutterError.
  final ValueChanged<PlatformException>? onCameraInitializeError;

  /// Called when the native torch state changes.
  ///
  /// This callback is currently supported only on iOS.
  final ValueChanged<bool>? onChangeFlashState;

  /// Optional absolute camera zoom ratio applied before the preview becomes visible.
  final double? initialZoomRatio;

  /// Initial torch state.
  final bool initialFlashEnabled;

  /// Optional recognition area retained for this scanner view.
  final CropRect? initialCropRect;

  /// Optional iOS camera retained until this scanner is initialized and active.
  final IosCamera? initialCamera;

  /// Creates a scanner view with optional initial camera configuration.
  const BarcodeScanner({
    required this.onScan,
    required this.onScannerInitialized,
    this.initialZoomRatio,
    this.initialFlashEnabled = false,
    this.initialCropRect,
    this.initialCamera,
    this.onCameraInitializeError,
    this.onChangeFlashState,
    Key? key,
  }) : super(key: key);

  @override
  _BarcodeScannerState createState() => _BarcodeScannerState();
}

class _BarcodeScannerState extends State<BarcodeScanner> with WidgetsBindingObserver {
  final _runtime = ScannerRuntime.instance;
  BarcodeScannerController? _barcodeScannerController;
  bool _isViewActive = false;
  StreamSubscription<Barcode>? _scanStreamSubscription;
  StreamSubscription<bool>? _toggleFlashStreamSubscription;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  Widget build(BuildContext context) {
    return CameraPreview(
      onCameraInitialized: _onCameraInitialized,
    );
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _syncCameraVisibility();
  }

  @override
  void activate() {
    super.activate();
    _syncCameraVisibility();
  }

  @override
  void deactivate() {
    _isViewActive = false;
    super.deactivate();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);

    _cancelSubscriptions();
    _barcodeScannerController?.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _syncCameraVisibility();
  }

  /// View registration allocates UI only. Setters in this callback update Dart state.
  Future<void> _onCameraInitialized(int viewId) async {
    if (!mounted) return;
    final controller = BarcodeScannerController(
      viewId: viewId,
      configuration: ScannerConfiguration(
        zoomRatio: widget.initialZoomRatio ?? 1,
        torchEnabled: widget.initialFlashEnabled,
        cropRect: widget.initialCropRect,
        iosCamera: widget.initialCamera,
      ),
    );
    _barcodeScannerController = controller;
    _scanStreamSubscription = controller.scanResults.listen((barcode) => widget.onScan(barcode));
    _toggleFlashStreamSubscription = controller.torchToggleStream.listen((enabled) => widget.onChangeFlashState?.call(enabled));
    widget.onScannerInitialized(controller);
    await _capture();
  }

  Future<void> _capture() async {
    final controller = _barcodeScannerController;
    if (controller == null || !_isViewActive) return;
    try {
      await _runtime.capture(controller);
    } on PlatformException catch (error) {
      if (!mounted) return;

      final onError = widget.onCameraInitializeError;
      if (onError != null) {
        onError.call(error);
        return;
      }
      rethrow;
    }
  }

  /// Maps route ticker visibility to explicit native camera ownership.
  void _syncCameraVisibility() {
    // Popup routes keep the underlying route onstage, while an opaque page
    // route disables tickers in the covered subtree.
    // TickerMode.valuesOf is unavailable in the minimum supported Flutter.
    final lifecycle = WidgetsBinding.instance.lifecycleState;
    // ignore: deprecated_member_use
    final isRouteVisible = TickerMode.of(context);
    final isAvailableAppLifecycle = !{AppLifecycleState.paused, AppLifecycleState.hidden, AppLifecycleState.detached}.contains(lifecycle);

    final active = isRouteVisible && isAvailableAppLifecycle;
    if (_isViewActive == active) return;
    _isViewActive = active;

    if (active) {
      unawaited(_capture());
    } else {
      final controller = _barcodeScannerController;
      if (controller != null) unawaited(_runtime.release(controller));
    }
  }

  /// Removes widget callbacks on disposal.
  void _cancelSubscriptions() {
    _scanStreamSubscription?.cancel();
    _scanStreamSubscription = null;
    _toggleFlashStreamSubscription?.cancel();
    _toggleFlashStreamSubscription = null;
  }
}
