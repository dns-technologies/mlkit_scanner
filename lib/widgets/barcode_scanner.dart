import 'dart:async';

import 'package:flutter/material.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';
import 'package:mlkit_scanner/models/recognition_type.dart';
import 'package:mlkit_scanner/platform/ml_kit_channel.dart';
import 'package:mlkit_scanner/widgets/camera_preview.dart';

/// Signature of the BarcodeScanner success initialize scanner function.
typedef BarcodeScannerInitializeCallback = void Function(BarcodeScannerController controller);

/// Widget for scanning barcodes using MLkit Barcode Scanning.
class BarcodeScanner extends StatefulWidget {
  /// Callback with barcode scanning result, when scanner detect a barcode.
  final ValueChanged<String> onScan;

  /// Callback on success scanner initialize, with [BarcodeScannerController] for control camera and detection.
  final BarcodeScannerInitializeCallback onScannerInitialized;

  /// Optional scanner overlay with [CropRect] of the detection area.
  final CropRect? cropOverlay;

  /// Callback if camera cannot be initialized.
  final CameraInitilizeError? onCameraInitializeError;

  /// Callback inform when change state of camera flash.
  ///
  /// Work only on IOS
  final ValueChanged<bool>? onChangeFlashState;

  const BarcodeScanner({
    required this.onScan,
    required this.onScannerInitialized,
    this.cropOverlay,
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
  StreamSubscription<String>? _scanStreamSubscription;
  StreamSubscription<bool>? _toggleFlashStreamSubscription;

  @override
  void initState() {
    super.initState();
    _channel = MlKitChannel();
    _barcodeScannerController = BarcodeScannerController._();
    _toggleFlashStreamSubscription = _channel.torchToggleStream.listen((event) => widget.onChangeFlashState?.call(event));
    _barcodeScannerController._attach(this);
  }

  @override
  void didUpdateWidget(covariant BarcodeScanner oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.cropOverlay != widget.cropOverlay && widget.cropOverlay != null) {
      _channel.setCropArea(widget.cropOverlay!);
    }
  }

  @override
  Widget build(BuildContext context) {
    return CameraPreview(
      onCameraInitializeError: widget.onCameraInitializeError,
      onCameraInitialized: _onCameraInitialized,
    );
  }

  @override
  void dispose() {
    _cancelScan();
    _toggleFlashStreamSubscription?.cancel();
    super.dispose();
  }

  @override
  void deactivate() {
    _barcodeScannerController._detach();
    super.deactivate();
  }

  void _onCameraInitialized() {
    if (widget.cropOverlay != null) {
      _channel.setCropArea(widget.cropOverlay!);
    }
    widget.onScannerInitialized(_barcodeScannerController);
  }

  Future<void> _toggleFlash() {
    return _channel.toggleFlash();
  }

  Future<void> _startScan(int delay) async {
    final scanStream = await _channel.startScan(RecognitionType.barcodeRecognition, delay);
    _scanStreamSubscription?.cancel();
    _scanStreamSubscription = scanStream.listen(widget.onScan);
  }

  Future<void> _cancelScan() async {
    await _channel.cancelScan();
    _scanStreamSubscription?.cancel();
    _scanStreamSubscription = null;
  }

  Future<void> _setDelay(int delay) {
    return _channel.setScanDelay(delay);
  }

  Future<void> _pauseCamera() {
    return _channel.pauseCamera();
  }

  Future<void> _resumeCamera() {
    return _channel.resumeCamera();
  }

  Future<void> _setZoom(double value) {
    return _channel.setZoom(value);
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
  /// `delay` -  delay in milliseconds between detection for decreasing CPU consumption.
  /// Detection happens every [delay] milliseconds, skipping frames during delay
  /// Can throw [PlatformException] if camera is not initialized.
  Future<void> startScan(int delay) async {
    return _barcodeScannerState?._startScan(delay);
  }

  /// Stop recognition of objects.
  Future<void> cancelScan() async {
    return _barcodeScannerState?._cancelScan();
  }

  /// Set delay between detections when scanning is active.
  ///
  /// `delay` -  delay in milliseconds between detection for decreasing CPU consumption.
  /// Detection happens every [delay] milliseconds, skipping frames during delay
  Future<void> setDelay(int delay) async {
    return _barcodeScannerState?._setDelay(delay);
  }

  /// Pause camera, also pause detection if scanning is active.
  ///
  /// For releasing resources of the camera use method [dispose].
  Future<void> pauseCamera() async {
    return _barcodeScannerState?._pauseCamera();
  }

  /// Resume camera, alse start detection if method [startScan] was called before pause.
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

  void _attach(_BarcodeScannerState state) {
    _barcodeScannerState = state;
  }

  void _detach() {
    _barcodeScannerState = null;
  }
}
