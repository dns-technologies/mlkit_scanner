import 'package:flutter/material.dart';

import '../platform/scanner_preview.dart';
import '../src/widgets/frozen_preview.dart';

/// Renders native camera pixels without creating or moving a Platform View.
class CameraPreview extends StatelessWidget {
  /// Shared output metadata, or null before native output is registered.
  final ScannerPreviewDescription? description;

  /// Retains this widget's own image while the native camera stays warm.
  final bool paused;

  /// Receives failures to retain this widget's paused preview image.
  final void Function(Object, StackTrace)? onError;

  /// Allows internal camera handoff to await this preview's retained pixels.
  final GlobalKey<FrozenPreviewState>? frameKey;

  /// Prevents copying another owner's first frame after this widget loses capture.
  final bool Function()? canRetainFrame;

  /// Creates a passive texture renderer without claiming camera ownership.
  const CameraPreview({super.key, required this.description, this.paused = false, this.onError, this.frameKey, this.canRetainFrame});

  /// Applies remaining source crop, rotation and mirroring before cover scaling.
  @override
  Widget build(BuildContext context) {
    const placeholder = SizedBox.expand(child: ColoredBox(color: Colors.black));
    return FrozenPreview(
      key: frameKey,
      paused: paused,
      ready: description != null && description!.status != ScannerPreviewStatus.starting,
      onError: onError ?? _reportError,
      canRetainFrame: canRetainFrame,
      placeholder: placeholder,
      child: _buildTexture(placeholder),
    );
  }

  /// Reports errors when this internal renderer is used without a scanner owner.
  void _reportError(Object error, StackTrace stack) {
    FlutterError.reportError(FlutterErrorDetails(exception: error, stack: stack, library: 'mlkit_scanner'));
  }

  /// Applies native geometry to the live texture before snapshotting its pixels.
  Widget _buildTexture(Widget placeholder) {
    final preview = description;
    if (preview == null || preview.status == ScannerPreviewStatus.starting) {
      return placeholder;
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
