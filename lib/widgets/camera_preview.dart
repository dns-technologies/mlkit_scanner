import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:mlkit_scanner/models/crop_rect.dart';
import 'package:mlkit_scanner/models/ios_camera.dart';

/// Signature for a platform view that is ready to capture the camera.
typedef CameraInitialized = void Function(int viewId);

/// Camera Preview of the device camera.
///
/// Widget automatically will dispose camera when called [dispose] in state.
class CameraPreview extends StatefulWidget {
  /// Callback when device camera initialize.
  final CameraInitialized onCameraInitialized;

  /// Optional normalized zoom applied before the preview becomes visible.
  final double? initialZoom;

  /// Optional torch state applied during camera initialization.
  final bool? initialFlashEnabled;

  /// Optional recognition area applied during camera initialization.
  final CropRect? initialCropRect;

  /// Optional camera used during initialization on iOS.
  final IosCamera? initialCamera;

  const CameraPreview({
    Key? key,
    required this.onCameraInitialized,
    this.initialZoom,
    this.initialFlashEnabled,
    this.initialCropRect,
    this.initialCamera,
  }) : super(key: key);

  @override
  _CameraPreviewState createState() => _CameraPreviewState();
}

class _CameraPreviewState extends State<CameraPreview> {
  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        if (defaultTargetPlatform == TargetPlatform.iOS) {
          return UiKitView(
            viewType: 'mlkit/camera_preview',
            onPlatformViewCreated: widget.onCameraInitialized,
            creationParamsCodec: const StandardMessageCodec(),
            creationParams: {
              'width': constraints.maxWidth,
              'height': constraints.maxHeight,
              if (widget.initialZoom != null)
                'initialZoom': widget.initialZoom,
              'initialFlashEnabled': widget.initialFlashEnabled,
              if (widget.initialCropRect != null)
                'initialCropRect': widget.initialCropRect!.toJson(),
              if (widget.initialCamera != null)
                'initialCamera': {
                  'position': widget.initialCamera!.position.code,
                  'type': widget.initialCamera!.type.code,
                },
            },
          );
        }
        return PlatformViewLink(
          viewType: 'mlkit/camera_preview',
          surfaceFactory: (context, controller) {
            return AndroidViewSurface(
              controller: controller as AndroidViewController,
              gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{
                Factory<TapGestureRecognizer>(TapGestureRecognizer.new),
                Factory<LongPressGestureRecognizer>(
                    LongPressGestureRecognizer.new),
              },
              hitTestBehavior: PlatformViewHitTestBehavior.opaque,
            );
          },
          onCreatePlatformView: (params) {
            return PlatformViewsService.initSurfaceAndroidView(
              id: params.id,
              viewType: 'mlkit/camera_preview',
              layoutDirection: TextDirection.ltr,
              creationParams: {
                'viewId': params.id,
                'width': constraints.maxWidth,
                'height': constraints.maxHeight,
                if (widget.initialZoom != null)
                  'initialZoom': widget.initialZoom,
                'initialFlashEnabled': widget.initialFlashEnabled,
                if (widget.initialCropRect != null)
                  'initialCropRect': widget.initialCropRect!.toJson(),
              },
              creationParamsCodec: const StandardMessageCodec(),
            )
              ..addOnPlatformViewCreatedListener((id) {
                params.onPlatformViewCreated(id);
                widget.onCameraInitialized(id);
              })
              ..create();
          },
        );
      },
    );
  }
}
