import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';

/// Retains a painted preview image while live rendering is paused or unavailable.
class FrozenPreview extends StatefulWidget {
  const FrozenPreview({
    super.key,
    required this.child,
    required this.paused,
    required this.ready,
    required this.onError,
    this.canRetainFrame,
  });

  /// Preview content to paint and retain.
  final Widget child;

  /// Whether to display a retained image instead of live content.
  final bool paused;

  /// Whether native pixels are ready for display and retention.
  final bool ready;

  /// Checks retention permission at capture time, including deferred post-frame callbacks.
  final bool Function()? canRetainFrame;

  /// Receives image capture failures.
  final void Function(Object, StackTrace) onError;

  @override
  State<FrozenPreview> createState() => FrozenPreviewState();
}

/// Owns the retained image and tracks completion of its rasterization.
class FrozenPreviewState extends State<FrozenPreview> {
  /// Isolates camera pixels from overlays and surrounding application content.
  final _boundaryKey = GlobalKey();

  /// Independent image that cannot change when the shared texture advances.
  ui.Image? _image;

  /// Pending capture of the paused preview image.
  Future<ui.Image>? _capture;

  /// Acknowledgment that the current capture succeeded or reported its error.
  Future<void>? _ready;

  @override
  void didUpdateWidget(covariant FrozenPreview oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.paused && widget.ready) {
      _image?.dispose();
      _image = null;
      _capture = null;
      _ready = null;
    } else if (widget.paused && oldWidget.ready) {
      retainFrame();
    }
  }

  /// Copies the last painted preview, including before a pending rebuild.
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

  @override
  void dispose() {
    _image?.dispose();
    super.dispose();
  }
}

/// Makes the last painted camera layer available independently of layout dirtiness.
class _PreviewBoundaryWidget extends SingleChildRenderObjectWidget {
  const _PreviewBoundaryWidget({super.key, required super.child, required this.ready});

  /// Whether the next paint contains a camera frame eligible for retention.
  final bool ready;

  @override
  RenderObject createRenderObject(BuildContext context) => _PreviewBoundary(ready);

  @override
  void updateRenderObject(BuildContext context, _PreviewBoundary renderObject) {
    renderObject.ready = ready;
  }
}

/// Provides snapshots of the last painted camera frame.
class _PreviewBoundary extends RenderRepaintBoundary {
  _PreviewBoundary(this._ready);

  /// Whether the child awaiting paint represents native camera output.
  bool _ready;

  /// Whether the existing layer actually contains painted camera output.
  bool _paintedCamera = false;

  /// Dimensions belonging to the retained layer, before any pending relayout.
  Size? _paintedSize;

  /// Requests a new paint when the child changes between loading and camera.
  set ready(bool value) {
    if (_ready == value) return;
    _ready = value;
    markNeedsPaint();
  }

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
