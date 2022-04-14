import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:mlkit_scanner/platform/ml_kit_channel.dart';

/// Signature for the CameraPreview error function.
typedef CameraInitilizeError = void Function(PlatformException);

/// Camera Preview of the device camera.
///
/// Widget automatically will dispose camera when called [dispose] in state.
class CameraPreview extends StatefulWidget {
  /// Callback when device camera initialize.
  final VoidCallback onCameraInitialized;

  /// Callback if camera cannot be initialized.
  final CameraInitilizeError? onCameraInitializeError;

  const CameraPreview({
    Key? key,
    required this.onCameraInitialized,
    this.onCameraInitializeError,
  }) : super(key: key);

  @override
  _CameraPreviewState createState() => _CameraPreviewState();
}

class _CameraPreviewState extends State<CameraPreview> {
  late MlKitChannel _channel;

  @override
  void initState() {
    super.initState();
    _channel = MlKitChannel();
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        _channel.updateConstraints(constraints.maxWidth, constraints.maxHeight);
        if (Platform.isIOS) {
          return UiKitView(
            viewType: 'mlkit/camera_preview',
            onPlatformViewCreated: _onViewCreated,
            creationParamsCodec: const StandardMessageCodec(),
            creationParams: {
              'width': constraints.maxWidth,
              'height': constraints.maxHeight,
            },
          );
        }
        return AndroidView(
          viewType: 'mlkit/camera_preview',
          onPlatformViewCreated: _onViewCreated,
          creationParamsCodec: const StandardMessageCodec(),
          creationParams: {
            'width': constraints.maxWidth,
            'height': constraints.maxHeight,
          },
        );
      },
    );
  }

  @override
  void dispose() {
    _channel.dispose();
    super.dispose();
  }

  Future<void> _onViewCreated(int id) async {
    try {
      await _channel.initCameraPreview();
      widget.onCameraInitialized();
    } on PlatformException catch (e) {
      widget.onCameraInitializeError?.call(e);
    }
  }
}
