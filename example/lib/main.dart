import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';

/// Starts the scanner example application.
void main() => runApp(const MyApp());

/// Formats camera failures without discarding their structured operation details.
String _describeError(Object error) {
  if (error is CameraControlException) {
    return '${error.operation.wireValue}: ${error.cause ?? error.message ?? error}';
  }
  return error.toString();
}

/// Demonstrates recognition and declarative camera controls.
class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) => const MaterialApp(home: _ScannerPage());
}

/// Displays the scanner preview and its controls.
class _ScannerPage extends StatefulWidget {
  const _ScannerPage();

  @override
  State<_ScannerPage> createState() => _ScannerPageState();
}

/// Keeps desired scanner settings in Flutter state.
class _ScannerPageState extends State<_ScannerPage> {
  /// Most recent barcode text; absent until a barcode has been recognized.
  String? _barcode;

  /// Magnifications cycled by the zoom button.
  static const _zoomRatios = [1.0, 2.0, 3.0];

  /// User-visible recognition cooldown choices in milliseconds.
  static const _delayOptions = {
    '0 milliseconds': 0,
    '100 milliseconds': 100,
    '500 milliseconds': 500,
    '2000 milliseconds': 2000,
  };

  /// Index of the selected demo magnification.
  int _zoomIndex = 0;

  /// Whether the scanner requests barcode recognition.
  bool _scanning = false;

  /// Whether preview and recognition are paused while the camera stays warm.
  bool _cameraPaused = false;

  /// Desired torch state applied through the widget configuration.
  bool _flashEnabled = false;

  /// Actual torch state reported by iOS, independently of desired settings.
  bool _actualFlashEnabled = false;

  /// Whether the preview uses the smaller demo recognition area.
  bool _cropEnabled = true;

  /// Cooldown after successful barcode recognition, in milliseconds.
  int _scanDelay = 0;

  /// Cameras returned by native iOS device discovery.
  List<IosCamera> _iosCameras = [];

  /// Selected camera index, or -1 for the platform default.
  int _cameraIndex = -1;

  /// Original scanner failure retained for display and explicit retry.
  Object? _error;

  /// Optional device-discovery failure, separate from scanner capture errors.
  Object? _discoveryError;

  /// Identity replaced only when retrying this scanner after an error.
  Key _scannerKey = UniqueKey();

  @override
  void initState() {
    super.initState();
    if (defaultTargetPlatform == TargetPlatform.iOS) {
      unawaited(_loadIosCameras());
    }
  }

  /// Loads native camera choices while preserving the default selection.
  Future<void> _loadIosCameras() async {
    try {
      final cameras = await MLKitUtils.getIosAvailableCameras();
      if (!mounted) return;
      setState(() => _iosCameras = cameras);
    } catch (error) {
      if (!mounted) return;
      setState(() => _discoveryError = error);
    }
  }

  /// Cycles through discovered cameras and null, which restores the default.
  void _setNextIosCamera() {
    if (_iosCameras.isEmpty) return;
    setState(() {
      _cameraIndex = _cameraIndex == _iosCameras.length - 1 ? -1 : _cameraIndex + 1;
      _zoomIndex = 0;
    });
  }

  /// Keeps the original error object delivered outside the widget build phase.
  void _handleError(Object error) {
    if (!mounted) return;
    setState(() => _error = error);
  }

  /// Recreates only the failed scanner with safe camera-control defaults.
  void _retryScanner() {
    setState(() {
      _error = null;
      _zoomIndex = 0;
      _flashEnabled = false;
      _cameraIndex = -1;
      _scannerKey = UniqueKey();
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('MLKit Scanner example')),
      body: ListView(children: [
        SizedBox(height: 240, child: _buildPreview()),
        _buildControls(),
      ]),
    );
  }

  /// Renders the scanner preview with a focus gesture hint.
  Widget _buildPreview() {
    final camera = _cameraIndex < 0 ? null : _iosCameras[_cameraIndex];
    return Stack(
      fit: StackFit.expand,
      children: [
        BarcodeScanner(
          key: _scannerKey,
          zoomRatio: _zoomRatios[_zoomIndex],
          flashEnabled: _flashEnabled,
          cropRect: _cropEnabled ? const CropRect(scaleHeight: .7, scaleWidth: .7) : null,
          camera: camera,
          cameraPaused: _cameraPaused,
          scanning: _scanning,
          scanDelay: _scanDelay,
          onScan: (code) => setState(() => _barcode = code.rawValue),
          onError: _handleError,
          onChangeFlashState: (enabled) => setState(() => _actualFlashEnabled = enabled),
        ),
        const IgnorePointer(
          child: Align(
            alignment: Alignment.topCenter,
            child: Padding(
              padding: EdgeInsets.all(8),
              child: Text(
                'Tap to focus / Long press to lock focus',
                style: TextStyle(color: Colors.white),
              ),
            ),
          ),
        ),
      ],
    );
  }

  /// Displays scanner settings, recognition results and errors.
  Widget _buildControls() {
    final camera = _cameraIndex < 0 ? null : _iosCameras[_cameraIndex];
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (_barcode case final barcode?)
          Padding(
            padding: const EdgeInsets.all(16),
            child: Text(barcode, style: const TextStyle(fontSize: 18)),
          ),
        if (_error case final error?)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Column(
              children: [
                Text(_describeError(error)),
                TextButton(
                  onPressed: _retryScanner,
                  child: const Text('Retry scanner'),
                ),
              ],
            ),
          ),
        Wrap(
          alignment: WrapAlignment.center,
          spacing: 8,
          children: [
            TextButton(
              onPressed: () => setState(() => _scanning = true),
              child: const Text('Start scan'),
            ),
            TextButton(
              onPressed: () => setState(() => _scanning = false),
              child: const Text('Cancel scan'),
            ),
            TextButton(
              onPressed: () => setState(() => _cameraPaused = true),
              child: const Text('Pause camera'),
            ),
            TextButton(
              onPressed: () => setState(() => _cameraPaused = false),
              child: const Text('Resume camera'),
            ),
            TextButton(
              onPressed: () => setState(() => _flashEnabled = !_flashEnabled),
              child: Text('Flash: ${_flashEnabled ? 'on' : 'off'}'),
            ),
            TextButton(
              onPressed: () => setState(() {
                _zoomIndex = (_zoomIndex + 1) % _zoomRatios.length;
              }),
              child: Text('Zoom: ${_zoomRatios[_zoomIndex]}x'),
            ),
            TextButton(
              onPressed: () => setState(() => _cropEnabled = !_cropEnabled),
              child: Text(_cropEnabled ? 'Use full frame' : 'Use crop'),
            ),
            PopupMenuButton<int>(
              tooltip: 'Set scan delay',
              onSelected: (delay) => setState(() => _scanDelay = delay),
              itemBuilder: (_) => [
                for (final entry in _delayOptions.entries) PopupMenuItem(value: entry.value, child: Text(entry.key)),
              ],
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Text(
                  'Delay: $_scanDelay ms',
                  style: Theme.of(context).textTheme.labelLarge?.copyWith(color: Theme.of(context).colorScheme.primary),
                ),
              ),
            ),
          ],
        ),
        if (defaultTargetPlatform == TargetPlatform.iOS) ...[
          TextButton(
            onPressed: _iosCameras.isEmpty ? null : _setNextIosCamera,
            child: Text(camera == null ? 'Camera: default' : 'Camera: ${camera.position.name}, ${camera.type.name}'),
          ),
          Center(child: Text('Actual torch: $_actualFlashEnabled')),
          if (_discoveryError case final error?)
            Padding(
              padding: const EdgeInsets.all(16),
              child: Text('Camera discovery: ${_describeError(error)}'),
            ),
        ],
      ],
    );
  }
}
