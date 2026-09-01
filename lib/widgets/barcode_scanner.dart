import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';
import 'package:mlkit_scanner/models/recognition_type.dart';
import 'package:mlkit_scanner/platform/ml_kit_channel.dart';
import 'package:mlkit_scanner/widgets/camera_preview.dart';

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
  /// here as [CameraControlException]. Errors from controller calls complete the
  /// corresponding [Future] instead.
  final ValueChanged<PlatformException>? onCameraInitializeError;

  /// Called when the native torch state changes.
  ///
  /// This callback is currently supported only on iOS.
  final ValueChanged<bool>? onChangeFlashState;

  /// Optional normalized zoom applied before the preview becomes visible.
  final double? initialZoom;

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
    this.initialZoom,
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

class _BarcodeScannerState extends State<BarcodeScanner> {
  late MlKitChannel _channel;
  late BarcodeScannerController _barcodeScannerController;
  StreamSubscription<Barcode>? _scanStreamSubscription;
  StreamSubscription<bool>? _toggleFlashStreamSubscription;
  int? _viewId;
  bool _isCameraVisible = false;

  @override
  void initState() {
    super.initState();
    _channel = MlKitChannel();
    _barcodeScannerController = BarcodeScannerController._();
    _barcodeScannerController._attach(this);
  }

  @override
  Widget build(BuildContext context) {
    return CameraPreview(
      initialZoom: widget.initialZoom,
      initialFlashEnabled: widget.initialFlashEnabled,
      initialCropRect: widget.initialCropRect,
      initialCamera: widget.initialCamera,
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
    _barcodeScannerController._attach(this);
    _syncCameraVisibility();
  }

  @override
  void deactivate() {
    _setCameraVisible(false);
    _barcodeScannerController._detach();
    super.deactivate();
  }

  @override
  void dispose() {
    _setCameraVisible(false);
    _barcodeScannerController._detach();
    _scanStreamSubscription?.cancel();
    _scanStreamSubscription = null;
    _toggleFlashStreamSubscription?.cancel();
    _toggleFlashStreamSubscription = null;
    super.dispose();
  }

  /// Subscribes to this native view's events and captures the visible camera.
  Future<void> _onCameraInitialized(int viewId) async {
    if (!mounted) return;
    _viewId = viewId;
    await _scanStreamSubscription?.cancel();
    if (!mounted) return;
    _scanStreamSubscription = _channel.scanResults(viewId).listen((barcode) {
      if (mounted && _viewId == viewId && _isCameraVisible) {
        widget.onScan(barcode);
      }
    });
    await _toggleFlashStreamSubscription?.cancel();
    if (!mounted) return;
    _toggleFlashStreamSubscription = _channel.torchToggleStream(viewId).listen((
      event,
    ) {
      if (mounted && _viewId == viewId && _isCameraVisible) {
        widget.onChangeFlashState?.call(event);
      }
    });

    _notifyScannerInitialized();
    if (_isCameraVisible) await _captureCameraAndHandleResult();
  }

  /// Captures this view and reports failures independently from controller creation.
  Future<void> _captureCameraAndHandleResult() async {
    try {
      await _captureCamera();
    } on PlatformException catch (error) {
      if (!mounted) return;
      final onError = widget.onCameraInitializeError;
      if (onError == null) rethrow;
      onError(error);
    }
  }

  /// Publishes the controller from the one-time native-view initialization path.
  void _notifyScannerInitialized() {
    if (!mounted) return;
    widget.onScannerInitialized(_barcodeScannerController);
  }

  /// Maps route ticker visibility to explicit native camera ownership.
  void _syncCameraVisibility() {
    // Popup routes keep the underlying route onstage, while an opaque page
    // route disables tickers in the covered subtree.
    // TickerMode.valuesOf is unavailable in the minimum supported Flutter.
    // ignore: deprecated_member_use
    _setCameraVisible(TickerMode.of(context));
  }

  /// Captures or releases the camera when this scanner's visibility changes.
  void _setCameraVisible(bool isVisible) {
    if (_isCameraVisible == isVisible) return;

    _isCameraVisible = isVisible;
    if (_isCameraVisible) {
      if (_viewId != null) {
        unawaited(_captureCameraAndHandleResult());
      }
    } else {
      unawaited(_releaseCamera());
    }
  }

  /// Toggles torch state for this native platform view.
  Future<void> _toggleFlash() {
    return _channel.toggleFlash(viewId: _requireViewId());
  }

  /// Captures camera ownership for this initialized native view.
  Future<void> _captureCamera() {
    return _channel.captureCamera(viewId: _requireViewId());
  }

  /// Releases camera ownership while retaining this view's configuration.
  Future<void> _releaseCamera() {
    final viewId = _viewId;
    if (viewId == null) return Future<void>.value();
    return _channel.releaseCamera(viewId: viewId);
  }

  /// Starts barcode recognition with the requested platform cooldown.
  Future<void> _startScan(int delay) {
    return _channel.startScan(
      RecognitionType.barcodeRecognition,
      delay,
      viewId: _requireViewId(),
    );
  }

  /// Stops barcode recognition without stopping the camera preview.
  Future<void> _cancelScan() {
    return _channel.cancelScan(viewId: _requireViewId());
  }

  /// Updates this view's successful-result cooldown.
  Future<void> _setDelay(int delay) {
    return _channel.setScanDelay(delay, viewId: _requireViewId());
  }

  /// Pauses camera work requested by this view.
  Future<void> _pauseCamera() {
    return _channel.pauseCamera(viewId: _requireViewId());
  }

  /// Resumes this view's retained camera and recognition intent.
  Future<void> _resumeCamera() {
    return _channel.resumeCamera(viewId: _requireViewId());
  }

  /// Applies normalized zoom to this view.
  Future<void> _setZoom(double value) {
    return _channel.setZoom(value, viewId: _requireViewId());
  }

  /// Applies normalized recognition geometry to this view.
  Future<void> _setCropArea(CropRect rect) {
    return _channel.setCropArea(rect, viewId: _requireViewId());
  }

  /// Selects a capture device for this view on iOS.
  Future<void> _setIosCamera({
    required IosCameraPosition position,
    required IosCameraType type,
  }) {
    return _channel.setIosCamera(
      viewId: _requireViewId(),
      position: position,
      type: type,
    );
  }

  /// Returns the initialized native view identifier or throws a [StateError].
  int _requireViewId() {
    final viewId = _viewId;
    if (viewId == null) {
      throw StateError('Camera preview is not initialized');
    }
    return viewId;
  }
}

/// Controls camera state and barcode recognition for one [BarcodeScanner].
///
/// Recognition begins only after [startScan] is called. [cancelScan] stops frame
/// analysis but keeps the preview available; [pauseCamera] also pauses camera
/// work. Configuration calls made while the scanner is hidden update its
/// retained state without changing the active camera. Calls made after the
/// controller detaches from its widget have no effect.
class BarcodeScannerController {
  _BarcodeScannerState? _barcodeScannerState;

  /// Creates a controller that is attached by its owning scanner state.
  BarcodeScannerController._();

  /// Runs a view-scoped command or completes immediately after detachment.
  Future<void> _withState(
    Future<void> Function(_BarcodeScannerState state) command,
  ) {
    final state = _barcodeScannerState;
    return state == null ? Future<void>.value() : command(state);
  }

  /// Toggles the selected camera's torch.
  ///
  /// When this scanner is hidden, the requested state is retained until its
  /// next camera capture.
  /// Throws a [PlatformException] when the selected camera has no flash and a
  /// [CameraControlException] when the torch operation fails.
  Future<void> toggleFlash() => _withState((state) => state._toggleFlash());

  /// Starts barcode recognition.
  ///
  /// [delay] is the minimum cooldown in milliseconds after successful
  /// recognition. On iOS, it also applies before the first attempt. Failed
  /// recognition does not restart this timer. On Android, failed attempts wait
  /// one second before analyzing the next available camera frame.
  /// A hidden scanner retains this request until it becomes active.
  Future<void> startScan(int delay) =>
      _withState((state) => state._startScan(delay));

  /// Stops recognition requested by this scanner widget.
  Future<void> cancelScan() => _withState((state) => state._cancelScan());

  /// Sets the delay applied after successful recognition.
  ///
  /// Failed recognition does not start this timer. A hidden scanner retains the
  /// value until it becomes active.
  Future<void> setDelay(int delay) =>
      _withState((state) => state._setDelay(delay));

  /// Pauses this scanner widget without changing another registered scanner.
  ///
  /// Camera ownership is released automatically when the scanner route is no
  /// longer visible or the widget is disposed.
  Future<void> pauseCamera() => _withState((state) => state._pauseCamera());

  /// Resumes this scanner widget and its previously requested detection state.
  ///
  /// For a hidden scanner this only retains resume intent; camera ownership is
  /// captured automatically when its route becomes visible again.
  /// Can throw [PlatformException] if the active camera cannot be resumed.
  Future<void> resumeCamera() => _withState((state) => state._resumeCamera());

  /// Sets the camera zoom.
  ///
  /// [value] must be in the inclusive range from `0` to `1`.
  /// A hidden scanner retains the value until its next camera capture.
  /// Throws [CameraControlException] when the zoom operation fails.
  Future<void> setZoom(double value) {
    assert(
      value >= 0 && value <= 1,
      "Value can only be in the range from 0 to 1",
    );
    return _withState((state) => state._setZoom(value));
  }

  /// Sets the detection area for barcode recognition.
  ///
  /// [rect] defines normalized scale and center offsets relative to the
  /// [CameraPreview]. If it partially exceeds the preview bounds, only its
  /// visible intersection is analyzed.
  /// Detection is skipped when the area is completely outside the preview.
  /// A hidden scanner retains the area until it becomes active.
  Future<void> setCropArea(CropRect rect) =>
      _withState((state) => state._setCropArea(rect));

  /// Selects an iOS camera with [position] and [type].
  ///
  /// A hidden scanner retains the selection until its next camera capture.
  /// This operation is unsupported on Android.
  Future<void> setIosCamera({
    required IosCameraPosition position,
    required IosCameraType type,
  }) =>
      _withState(
        (state) => state._setIosCamera(position: position, type: type),
      );

  /// Attaches this controller to the currently mounted scanner state.
  void _attach(_BarcodeScannerState state) {
    _barcodeScannerState = state;
  }

  /// Detaches the controller so later calls safely become no-ops.
  void _detach() {
    _barcodeScannerState = null;
  }
}
