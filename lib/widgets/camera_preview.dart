import 'package:flutter/material.dart';

import '../platform/scanner_preview.dart';
import '../src/widgets/frozen_preview.dart';

/// Renders a camera texture with its display transforms and an optional retained frame.
class CameraPreview extends StatelessWidget {
  const CameraPreview({super.key, required this.description, this.paused = false, this.onError, this.frameKey, this.canRetainFrame});

  /// Shared output metadata, or null before native output is registered.
  final ScannerPreviewDescription? description;

  /// Whether to display a retained image instead of live texture frames.
  final bool paused;

  /// Receives failures to retain this widget's paused preview image.
  final void Function(Object, StackTrace)? onError;

  /// Key for accessing retained-frame state and awaiting image capture.
  final GlobalKey<FrozenPreviewState>? frameKey;

  /// Determines whether the current frame may be retained.
  final bool Function()? canRetainFrame;

  @override
  Widget build(BuildContext context) => FrozenPreview(
    key: frameKey,
    paused: paused,
    // Streaming metadata allows the live texture to be displayed.
    // FrozenPreview keeps this widget's own paused snapshot independently.
    ready: description?.status == ScannerPreviewStatus.streaming,
    onError: onError ?? _reportError,
    canRetainFrame: canRetainFrame,
    child: _buildTexture(),
  );

  /// Delivers retention failures to the callback or Flutter's error handler.
  void _reportError(Object error, StackTrace stack) {
    FlutterError.reportError(FlutterErrorDetails(exception: error, stack: stack, library: 'mlkit_scanner'));
  }

  /// Builds the live preview in its display orientation and visible bounds.
  Widget _buildTexture() {
    final preview = description;
    if (preview == null) {
      return const SizedBox.expand();
    }
    Widget image = SizedBox(
      width: preview.size.width,
      height: preview.size.height,
      child: Texture(textureId: preview.textureId, freeze: paused || preview.status == ScannerPreviewStatus.paused),
    );
    if (preview.cropRect case final crop?) {
      image = SizedBox(
        width: crop.width,
        height: crop.height,
        child: ClipRect(
          child: Stack(
            children: [Positioned(left: -crop.left, top: -crop.top, width: preview.size.width, height: preview.size.height, child: image)],
          ),
        ),
      );
    }
    image = RotatedBox(quarterTurns: preview.rotationDegrees ~/ 90, child: image);
    if (preview.mirrored) image = Transform.flip(flipX: true, child: image);
    return ClipRect(child: SizedBox.expand(child: FittedBox(fit: BoxFit.cover, child: image)));
  }
}
