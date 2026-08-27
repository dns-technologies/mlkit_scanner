import 'dart:async';

import 'package:flutter/material.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';
import 'package:mlkit_scanner/models/recognition_type.dart';
import 'package:mlkit_scanner/platform/ml_kit_channel.dart';
import 'package:mlkit_scanner/widgets/camera_preview.dart';

/// Signature of the BarcodeScanner success initialize scanner function.
typedef BarcodeScannerInitializeCallback = void Function(
    BarcodeScannerController controller);

/// Widget for scanning barcodes using MLkit Barcode Scanning.
class BarcodeScanner extends StatefulWidget {
  /// Callback with barcode scanning result, when scanner detect a barcode.
  final ValueChanged<Barcode> onScan;

  /// Callback on success scanner initialize, with [BarcodeScannerController] for control camera and detection.
  final BarcodeScannerInitializeCallback onScannerInitialized;

  /// Callback if camera cannot be initialized.
  final CameraInitilizeError? onCameraInitializeError;

  /// Callback inform when change state of camera flash.
  ///
  /// Work only on IOS
  final ValueChanged<bool>? onChangeFlashState;

  /// Optional normalized zoom applied before the preview becomes visible.
  final double? initialZoom;

  /// Initial torch state.
  final bool initialFlashEnabled;

  /// Optional recognition area applied during camera initialization.
  final CropRect? initialCropRect;

  /// Optional camera used during initialization on iOS.
  final IosCamera? initialCamera;

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
      onCameraInitializeError: widget.onCameraInitializeError,
      onCameraInitialized: _onCameraInitialized,
    );
  }

  @override
  void activate() {
    _barcodeScannerController._attach(this);
    super.activate();
  }

  @override
  void deactivate() {
    _barcodeScannerController._detach();
    super.deactivate();
  }

  @override
  void dispose() {
    _scanStreamSubscription?.cancel();
    _scanStreamSubscription = null;
    _toggleFlashStreamSubscription?.cancel();
    super.dispose();
  }

  Future<void> _onCameraInitialized(int viewId) async {
    if (!mounted) return;
    _viewId = viewId;
    await _scanStreamSubscription?.cancel();
    if (!mounted) return;
    _scanStreamSubscription = _channel
        .scanResults(viewId)
        .listen((barcode) => widget.onScan(barcode));
    await _toggleFlashStreamSubscription?.cancel();
    if (!mounted) return;
    _toggleFlashStreamSubscription = _channel
        .torchToggleStream(viewId)
        .listen((event) => widget.onChangeFlashState?.call(event));
    widget.onScannerInitialized(_barcodeScannerController);
  }

  Future<void> _toggleFlash() {
    return _channel.toggleFlash(viewId: _requireViewId());
  }

  Future<void> _startScan(int delay) async {
    await _channel.startScan(
      RecognitionType.barcodeRecognition,
      delay,
      viewId: _requireViewId(),
    );
  }

  Future<void> _cancelScan() async {
    await _channel.cancelScan(viewId: _requireViewId());
  }

  Future<void> _setDelay(int delay) {
    return _channel.setScanDelay(delay, viewId: _requireViewId());
  }

  Future<void> _pauseCamera() {
    return _channel.pauseCamera(viewId: _requireViewId());
  }

  Future<void> _resumeCamera() {
    return _channel.resumeCamera(viewId: _requireViewId());
  }

  Future<void> _setZoom(double value) {
    return _channel.setZoom(value, viewId: _requireViewId());
  }

  Future<void> _setCropArea(CropRect rect) {
    return _channel.setCropArea(rect, viewId: _requireViewId());
  }

  Future<void> _setIosCamera({
    required IosCameraPosition position,
    required IosCameraType type,
  }) {
    return _channel.setIosCamera(position: position, type: type);
  }

  int _requireViewId() {
    final viewId = _viewId;
    if (viewId == null) {
      throw StateError('Camera preview is not initialized');
    }
    return viewId;
  }
}

/// Controller for control camera and detection. Return by widget [BarcodeScanner] when scanner is initialized.
///
/// Detection will start only after call method [startScan]. After call [cancelScan] or no call of [startScan] there is no detection,
/// which saves resources of the device. [cancelScan] doens't stop the cameraPreview - only detection, to stop a camera
/// use method [pauseCamera].
class BarcodeScannerController {
  _BarcodeScannerState? _barcodeScannerState;

  BarcodeScannerController._();

  /// Toggle flash of the device.
  ///
  /// Can throw a [PlatformException] if doesn't have flash.
  Future<void> toggleFlash() async {
    return _barcodeScannerState?._toggleFlash();
  }

  /// Start recognition objects of type [RecognitionType]
  ///
  /// `delay` - minimum delay in milliseconds after successful recognition.
  /// Failed recognition does not start this timer. On Android, failed attempts
  /// wait one second before analyzing the next available camera frame.
  /// Can throw [PlatformException] if camera is not initialized.
  Future<void> startScan(int delay) async {
    return _barcodeScannerState?._startScan(delay);
  }

  /// Stops recognition requested by this scanner widget.
  Future<void> cancelScan() async {
    return _barcodeScannerState?._cancelScan();
  }

  /// Sets the delay applied after successful recognition.
  ///
  /// Failed recognition does not start this timer.
  Future<void> setDelay(int delay) async {
    return _barcodeScannerState?._setDelay(delay);
  }

  /// Pauses this scanner widget without changing another registered scanner.
  ///
  /// For releasing resources of the camera use method [dispose].
  Future<void> pauseCamera() async {
    return _barcodeScannerState?._pauseCamera();
  }

  /// Resumes this scanner widget and its previously requested detection state.
  ///
  /// Can throw [PlatformException] if camera is not initialized.
  Future<void> resumeCamera() async {
    return _barcodeScannerState?._resumeCamera();
  }

  /// Sets the camera zoom.
  ///
  /// Value can only be in the range from 0 to 1
  Future<void> setZoom(double value) async {
    assert(
      value >= 0 && value <= 1,
      "Value can only be in the range from 0 to 1",
    );
    return _barcodeScannerState?._setZoom(value);
  }

  /// Sets the detection area for barcode recognition.
  ///
  /// The [rect] parameter defines the crop area relative to the [CameraPreview] size
  /// using scale and offset values in percentage. If the area partially exceeds
  /// the [CameraPreview] bounds, only its visible intersection is analyzed.
  /// Detection is skipped when the area is completely outside the preview.
  ///
  /// Can throw [PlatformException] if camera is not initialized.
  Future<void> setCropArea(CropRect rect) async {
    return _barcodeScannerState?._setCropArea(rect);
  }

  /// Sets iOS camera with [position] and [type].
  Future<void> setIosCamera({
    required IosCameraPosition position,
    required IosCameraType type,
  }) async {
    return await _barcodeScannerState?._setIosCamera(
      position: position,
      type: type,
    );
  }

  void _attach(_BarcodeScannerState state) {
    _barcodeScannerState = state;
  }

  void _detach() {
    _barcodeScannerState = null;
  }
}
