import 'dart:math' as math;

import 'package:flutter/material.dart';
import '../models/crop_rect.dart';

/// Recognition-area overlay with focus gestures and animated feedback.
class ScannerOverlay extends StatefulWidget {
  const ScannerOverlay({
    super.key,
    required this.crop,
    required this.scanning,
    required this.focusEnabled,
    required this.onFocus,
    required this.onLockFocus,
  });

  /// Recognition area whose center also anchors focus feedback.
  final CropRect crop;

  /// Selects the active recognition color for crop corners.
  final bool scanning;

  /// Whether focus gestures are enabled.
  final bool focusEnabled;

  /// Requests continuous autofocus at the crop center.
  final VoidCallback onFocus;

  /// Requests locked autofocus at the crop center.
  final VoidCallback onLockFocus;

  @override
  State<ScannerOverlay> createState() => _ScannerOverlayState();
}

/// Owns transient circle feedback independently of the persistent focus lock.
class _ScannerOverlayState extends State<ScannerOverlay> with TickerProviderStateMixin {
  /// Transient focus feedback that fades away even while focus remains locked.
  late final _circleAnimation = AnimationController(vsync: this, duration: const Duration(seconds: 1));

  /// Shared timeline for the lock's appearance and corner flight.
  late final _lockAnimation = AnimationController(vsync: this, duration: const Duration(milliseconds: 800));

  /// Fades the lock at its current position when autofocus is restored.
  late final _unlockAnimation = AnimationController(vsync: this, duration: const Duration(milliseconds: 200));

  /// Routes focus animation ticks straight to paint, bypassing widget build and layout.
  late final _animations = Listenable.merge([_circleAnimation, _lockAnimation, _unlockAnimation]);

  /// Keeps repeat long presses from restarting a settled lock's flight.
  bool _focusLocked = false;

  /// Requests static feedback when the user disables animations.
  bool get _disableAnimations => MediaQuery.disableAnimationsOf(context);

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_disableAnimations) {
      _circleAnimation.value = 1;
      _lockAnimation.value = _focusLocked ? 1 : 0;
      _unlockAnimation.value = 0;
    }
  }

  @override
  void didUpdateWidget(ScannerOverlay oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.focusEnabled) {
      _focusLocked = false;
      _circleAnimation.reset();
      _lockAnimation.reset();
      _unlockAnimation.reset();
    }
  }

  /// Restarts the circle for each accepted gesture and forwards its focus mode.
  void _focus(bool locked) {
    if (!widget.focusEnabled) return;
    if (_disableAnimations) {
      _circleAnimation.value = 1;
    } else {
      _circleAnimation.forward(from: 0);
    }
    _animateLock(locked);
    _focusLocked = locked;
    if (locked) {
      widget.onLockFocus();
    } else {
      widget.onFocus();
    }
  }

  /// Starts a new lock flight or fades the existing lock without moving it back.
  void _animateLock(bool locked) {
    if (locked && !_focusLocked) {
      _unlockAnimation.reset();
      if (_disableAnimations) {
        _lockAnimation.value = 1;
      } else {
        _lockAnimation.forward(from: 0);
      }
    } else if (!locked) {
      // Fade at the current position, including a tap during the flight.
      _lockAnimation.stop();
      if (_disableAnimations) {
        _unlockAnimation.value = 1;
      } else if (_focusLocked) {
        _unlockAnimation.forward(from: 0);
      }
    }
  }

  @override
  Widget build(BuildContext context) => GestureDetector(
    behavior: HitTestBehavior.opaque,
    onTap: widget.focusEnabled ? () => _focus(false) : null,
    onLongPress: widget.focusEnabled ? () => _focus(true) : null,
    child: CustomPaint(
      painter: _OverlayPainter(widget.crop, widget.scanning),
      foregroundPainter: _FocusPainter(
        crop: widget.crop,
        circleAnimation: _circleAnimation,
        lockAnimation: _lockAnimation,
        unlockAnimation: _unlockAnimation,
        repaint: _animations,
      ),
    ),
  );

  @override
  void dispose() {
    _circleAnimation.dispose();
    _lockAnimation.dispose();
    _unlockAnimation.dispose();
    super.dispose();
  }
}

/// Cosine easing with symmetric acceleration and deceleration.
class _FocusCurve extends Curve {
  const _FocusCurve();

  @override
  double transformInternal(double t) => t == .5 ? .5 : (1 - math.cos(math.pi * t)) / 2;
}

/// Draws the recognition mask and corner strokes in widget coordinates.
class _OverlayPainter extends CustomPainter {
  _OverlayPainter(this.crop, this.scanning);

  /// Normalized recognition rectangle relative to the current viewport.
  final CropRect crop;

  /// Chooses active or idle crop-corner color.
  final bool scanning;

  @override
  void paint(Canvas canvas, Size size) {
    final bounds = Offset.zero & size;
    final center = Offset(size.width * (1 + crop.offsetX) / 2, size.height * (1 + crop.offsetY) / 2);
    final area = Rect.fromCenter(center: center, width: size.width * crop.scaleWidth, height: size.height * crop.scaleHeight);
    canvas.save();
    canvas.clipRect(bounds);
    _drawMask(canvas, bounds, area);
    final border =
        Paint()
          ..color = scanning ? const Color(0xff43a047) : const Color(0xff616161)
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2
          ..strokeCap = StrokeCap.round;
    final length = area.width * .05;
    for (final corner in [area.topLeft, area.topRight, area.bottomLeft, area.bottomRight]) {
      final dx = corner.dx == area.left ? length : -length;
      final dy = corner.dy == area.top ? length : -length;
      canvas.drawPath(
        Path()
          ..moveTo(corner.dx + dx, corner.dy)
          ..lineTo(corner.dx, corner.dy)
          ..lineTo(corner.dx, corner.dy + dy),
        border,
      );
    }
    canvas.restore();
  }

  void _drawMask(Canvas canvas, Rect bounds, Rect area) {
    final paint = Paint()..color = const Color(0x78000000);
    final contained =
        !area.isEmpty && area.left >= bounds.left && area.top >= bounds.top && area.right <= bounds.right && area.bottom <= bounds.bottom;
    // Preserve the original clipping behavior for crops extending outside the preview.
    if (!contained) {
      final mask =
          Path()
            ..fillType = PathFillType.evenOdd
            ..addRect(bounds)
            ..addRect(area);
      canvas.drawPath(mask, paint);
      return;
    }

    // Rectangles reduce the cost of replaying the mask for each Impeller preview frame.
    final bands = [
      Rect.fromLTRB(bounds.left, bounds.top, area.left, bounds.bottom),
      Rect.fromLTRB(area.right, bounds.top, bounds.right, bounds.bottom),
      Rect.fromLTRB(area.left, bounds.top, area.right, area.top),
      Rect.fromLTRB(area.left, area.bottom, area.right, bounds.bottom),
    ];
    for (final band in bands) {
      if (!band.isEmpty) canvas.drawRect(band, paint);
    }
  }

  @override
  bool shouldRepaint(_OverlayPainter old) => old.crop != crop || old.scanning != scanning;
}

/// Paints one frame of focus feedback without changing its animation state.
class _FocusPainter extends CustomPainter {
  _FocusPainter({
    required this.crop,
    required this.circleAnimation,
    required this.lockAnimation,
    required this.unlockAnimation,
    required super.repaint,
  });

  /// Easing shared by the circle pulse and the lock.
  static const _curve = _FocusCurve();

  /// The lock fades in for 300 ms and then flies to the corner for 500 ms.
  static const _fadeEnd = .375;

  /// Shared focus-circle radius in logical pixels.
  static const _radius = 35.0;

  /// Top and left inset of the settled lock icon, in logical pixels.
  static const _lockOffset = 19.2;

  /// Distance from focus center to the lock's initial center.
  static const _lockDistance = 64.0;

  /// Lock outline in local icon coordinates, before placement in the preview.
  static final _lockPath =
      Path()
        ..fillType = PathFillType.evenOdd
        ..addRRect(RRect.fromRectAndRadius(const Rect.fromLTRB(3.6667, 8, 20.3334, 22), const Radius.circular(2)))
        ..addRect(const Rect.fromLTRB(5.75, 10, 18.25, 20))
        ..moveTo(6.7917, 8)
        ..lineTo(6.7917, 6)
        ..cubicTo(6.7917, 3.24, 9.125, 1, 12, 1)
        ..cubicTo(14.875, 1, 17.2084, 3.24, 17.2084, 6)
        ..lineTo(17.2084, 8)
        ..lineTo(15.125, 8)
        ..lineTo(15.125, 6)
        ..cubicTo(15.125, 4.34, 13.7292, 3, 12, 3)
        ..cubicTo(10.2709, 3, 8.875, 4.34, 8.875, 6)
        ..lineTo(8.875, 8)
        ..close()
        ..addOval(const Rect.fromLTRB(9.9167, 13, 14.0834, 17));

  /// Recognition geometry whose center anchors the circle and initial lock.
  final CropRect crop;

  /// Timeline of the circle pulse.
  final Animation<double> circleAnimation;

  /// Timeline for the lock appearance and travel.
  final Animation<double> lockAnimation;

  /// Timeline for the fade when autofocus is restored.
  final Animation<double> unlockAnimation;

  @override
  void paint(Canvas canvas, Size size) {
    final circleProgress = circleAnimation.value;
    final pulse = circleProgress <= .5 ? circleProgress * 2 : (1 - circleProgress) * 2;
    final circleOpacity = _curve.transform(pulse);
    final lockProgress = lockAnimation.value;
    final lockOpacity = const Interval(0, _fadeEnd, curve: _curve).transform(lockProgress) * (1 - _curve.transform(unlockAnimation.value));
    if (circleOpacity == 0 && lockOpacity == 0) return;

    final center = Offset(size.width * (1 + crop.offsetX) / 2, size.height * (1 + crop.offsetY) / 2);
    canvas.save();
    canvas.clipRect(Offset.zero & size);
    if (circleOpacity > 0) {
      canvas.drawCircle(
        center,
        _radius,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1.5
          ..color = Colors.white.withAlpha((255 * circleOpacity).round()),
      );
    }
    if (lockOpacity > 0) {
      final lockTravel = const Interval(_fadeEnd, 1, curve: _curve).transform(lockProgress);
      final origin = Offset.lerp(center.translate(-_lockDistance - 12, -12), const Offset(_lockOffset, _lockOffset), lockTravel)!;
      canvas.translate(origin.dx, origin.dy);
      canvas.drawPath(_lockPath, Paint()..color = Colors.white.withAlpha((255 * lockOpacity).round()));
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(_FocusPainter old) =>
      old.crop != crop ||
      old.circleAnimation != circleAnimation ||
      old.lockAnimation != lockAnimation ||
      old.unlockAnimation != unlockAnimation;
}
