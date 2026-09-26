import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';

/// Retains this widget's pixels while other widgets keep using the live texture.
class FrozenPreview extends StatefulWidget {
  /// Camera pixels after crop, rotation and fitting, without scanner controls.
  final Widget child;

  /// Whether to retain a single image until this widget resumes.
  final bool paused;

  /// Whether the current capture and native frame are ready for display and retention.
  final bool ready;

  /// Checks ownership at capture time, including deferred post-frame callbacks.
  final bool Function()? canRetainFrame;

  /// Reports image capture failures to the owning scanner.
  final void Function(Object, StackTrace) onError;

  /// Creates a per-widget frame owner without changing the camera lifetime.
  const FrozenPreview({
    super.key,
    required this.child,
    required this.paused,
    required this.ready,
    required this.onError,
    this.canRetainFrame,
  });

  /// Creates the owner of the retained GPU image.
  @override
  State<FrozenPreview> createState() => FrozenPreviewState();
}

/// Owns the frame and lets camera handoff wait for rasterization to finish.
class FrozenPreviewState extends State<FrozenPreview> {
  /// Isolates camera pixels from overlays and surrounding application content.
  final _boundaryKey = GlobalKey();

  /// Independent image that cannot change when the shared texture advances.
  ui.Image? _image;

  /// Current capture operation; its identity rejects results after resume.
  Future<ui.Image>? _capture;

  /// Acknowledgment that the current capture succeeded or reported its error.
  Future<void>? _ready;

  /// Captures the previous paint before a pause rebuild can replace its layers.
  @override
  void didUpdateWidget(covariant FrozenPreview oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.paused) {
      _image?.dispose();
      _image = null;
      _capture = null;
      _ready = null;
    } else if (oldWidget.ready) {
      retainFrame();
    }
  }

  /// Copies the last painted preview, including when handoff precedes rebuild.
  /// A widget without a painted camera frame has nothing to retain yet.
  Future<void> retainFrame() {
    if (_ready case final ready?) return ready;
    if (!widget.ready || !(widget.canRetainFrame?.call() ?? true)) {
      return Future.value();
    }
    final boundary = _boundaryKey.currentContext?.findRenderObject() as _PreviewBoundary?;
    final capture = boundary?.capture(MediaQuery.devicePixelRatioOf(context));
    if (capture == null) return Future.value();
    _capture = capture;
    return _ready = capture.then(
      (image) {
        if (!mounted || !identical(_capture, capture)) {
          image.dispose();
          return;
        }
        setState(() => _image = image);
      },
      onError: (Object error, StackTrace stack) {
        if (mounted && identical(_capture, capture)) {
          widget.onError(error, stack);
        }
      },
    );
  }

  /// Keeps the live child mounted while only the retained image is painted.
  @override
  Widget build(BuildContext context) {
    if (widget.paused && widget.ready && _capture == null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && widget.paused) retainFrame();
      });
    }
    if (_image == null && (!widget.ready || (widget.paused && !(widget.canRetainFrame?.call() ?? true)))) {
      return const SizedBox.expand(child: ColoredBox(color: Color(0xFF000000)));
    }
    return Stack(
      fit: StackFit.expand,
      children: [
        Offstage(offstage: _image != null, child: _PreviewBoundaryWidget(key: _boundaryKey, ready: widget.ready, child: widget.child)),
        if (_image case final image?) RawImage(image: image, fit: BoxFit.cover),
      ],
    );
  }

  /// Releases retained pixels even when a paused route is removed directly.
  @override
  void dispose() {
    _image?.dispose();
    super.dispose();
  }
}

/// Makes the last painted camera layer available independently of layout dirtiness.
class _PreviewBoundaryWidget extends SingleChildRenderObjectWidget {
  /// Whether the next paint contains a camera frame eligible for retention.
  final bool ready;

  /// Wraps only camera content, never scanner overlays.
  const _PreviewBoundaryWidget({super.key, required super.child, required this.ready});

  /// Creates a boundary that remembers the dimensions of its last paint.
  @override
  RenderObject createRenderObject(BuildContext context) => _PreviewBoundary(ready);

  /// Associates readiness with the next actual paint, not the previous layers.
  @override
  void updateRenderObject(BuildContext context, _PreviewBoundary renderObject) {
    renderObject.ready = ready;
  }
}

/// Snapshots already painted layers without relying on debug-only paint flags.
class _PreviewBoundary extends RenderRepaintBoundary {
  /// Whether the child awaiting paint represents native camera output.
  bool _ready;

  /// Requests a new paint when the child changes between loading and camera.
  set ready(bool value) {
    if (_ready == value) return;
    _ready = value;
    markNeedsPaint();
  }

  /// Whether the existing layer actually contains painted camera output.
  bool _paintedCamera = false;

  /// Dimensions belonging to the retained layer, before any pending relayout.
  Size? _paintedSize;

  /// Creates a camera-only snapshot boundary.
  _PreviewBoundary(this._ready);

  /// Records geometry only after the camera content has been painted.
  @override
  void paint(PaintingContext context, Offset offset) {
    super.paint(context, offset);
    _paintedSize = size;
    _paintedCamera = _ready;
  }

  /// Submits the painted scene now and acknowledges completed rasterization.
  Future<ui.Image>? capture(double pixelRatio) {
    final paintedSize = _paintedSize;
    final paintedLayer = layer;
    if (!_paintedCamera || paintedSize == null || paintedSize.isEmpty || paintedLayer == null) {
      return null;
    }
    return (paintedLayer as OffsetLayer).toImage(Offset.zero & paintedSize, pixelRatio: pixelRatio);
  }
}
