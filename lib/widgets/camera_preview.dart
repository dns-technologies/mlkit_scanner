import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

/// Reports UI registration; acquiring the camera is a separate operation.
typedef CameraInitialized = FutureOr<void> Function(int viewId);

/// Flutter host for a native preview container. No scanner configuration crosses creation.
class CameraPreview extends StatelessWidget {
  final CameraInitialized onCameraInitialized;

  const CameraPreview({super.key, required this.onCameraInitialized});

  @override
  Widget build(BuildContext context) {
    if (defaultTargetPlatform == TargetPlatform.iOS) {
      return UiKitView(
        viewType: 'mlkit/camera_preview',
        onPlatformViewCreated: (id) => onCameraInitialized(id),
      );
    }
    return PlatformViewLink(
      viewType: 'mlkit/camera_preview',
      surfaceFactory: (context, controller) => AndroidViewSurface(
        controller: controller as AndroidViewController,
        gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{
          Factory<TapGestureRecognizer>(TapGestureRecognizer.new),
          Factory<LongPressGestureRecognizer>(LongPressGestureRecognizer.new),
        },
        hitTestBehavior: PlatformViewHitTestBehavior.opaque,
      ),
      onCreatePlatformView: (params) {
        return PlatformViewsService.initSurfaceAndroidView(
          id: params.id,
          viewType: 'mlkit/camera_preview',
          layoutDirection: TextDirection.ltr,
          creationParams: {'viewId': params.id},
          creationParamsCodec: const StandardMessageCodec(),
        )
          ..addOnPlatformViewCreatedListener((id) {
            params.onPlatformViewCreated(id);
            onCameraInitialized(id);
          })
          ..create();
      },
    );
  }
}
