import 'dart:math' as math;

import 'package:flutter/material.dart';
import '../models/crop_rect.dart';

/// Recognition area and focus feedback, in the same coordinates as the Flutter preview.
class ScannerOverlay extends StatefulWidget {
  /// Recognition area whose center also anchors focus feedback.
  final CropRect crop;

  /// Selects the active recognition color for crop corners.
  final bool scanning;

  /// Allows gestures only for the visible widget with an active capture.
  final bool focusEnabled;

  /// Requests continuous autofocus at the crop center.
  final VoidCallback onFocus;

  /// Requests locked autofocus at the crop center.
  final VoidCallback onLockFocus;

  const ScannerOverlay({
    super.key,
    required this.crop,
    required this.scanning,
    required this.focusEnabled,
    required this.onFocus,
    required this.onLockFocus,
  });

  @override
  State<ScannerOverlay> createState() => _ScannerOverlayState();
}

/// Owns transient circle feedback independently of the persistent focus lock.
class _ScannerOverlayState extends State<ScannerOverlay> with TickerProviderStateMixin {
  /// One second pulse: 500 ms fade-in followed by 500 ms fade-out.
  late final _circleAnimation = AnimationController(vsync: this, duration: const Duration(seconds: 1));

  /// Platform-specific timeline for the lock's appearance and corner flight.
  late final _lockAnimation = AnimationController(vsync: this);

  /// Fades the lock at its current position when autofocus is restored.
  late final _unlockAnimation = AnimationController(vsync: this, duration: const Duration(milliseconds: 200));

  /// Rebuilds only the overlay when any feedback animation advances.
  late final _animations = Listenable.merge([_circleAnimation, _lockAnimation, _unlockAnimation]);

  /// Keeps repeat long presses from restarting a settled lock's flight.
  bool _focusLocked = false;

  /// Selects the original iOS timing, dimensions and corner placement.
  bool get _usesIosAnimations => Theme.of(context).platform == TargetPlatform.iOS;

  /// Requests static feedback when the user disables animations.
  bool get _disableAnimations => MediaQuery.disableAnimationsOf(context);

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _lockAnimation.duration = Duration(milliseconds: _usesIosAnimations ? 1000 : 800);
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
    child: AnimatedBuilder(animation: _animations, builder: _buildFeedback),
  );

  /// Converts the native focus timelines into circle, lock alpha and flight values.
  Widget _buildFeedback(BuildContext context, Widget? child) {
    final ios = _usesIosAnimations;
    final curve = ios ? Curves.linear : const _AndroidFocusCurve();
    final pulse = _circleAnimation.value <= .5 ? _circleAnimation.value * 2 : (1 - _circleAnimation.value) * 2;
    // iOS fades for 250/1000 ms; Android fades for 300/800 ms.
    final fadeEnd = ios ? .25 : .375;
    return CustomPaint(
      painter: _OverlayPainter(widget.crop, widget.scanning),
      foregroundPainter: _FocusPainter(
        crop: widget.crop,
        radius: ios ? 40 : 35,
        lockOffset: ios ? 20 : 19.2,
        lockDistance: ios ? 64 : 30,
        circleOpacity: curve.transform(pulse),
        lockOpacity: Interval(0, fadeEnd, curve: curve).transform(_lockAnimation.value) * (1 - curve.transform(_unlockAnimation.value)),
        lockTravel: Interval(fadeEnd, 1, curve: curve).transform(_lockAnimation.value),
      ),
    );
  }

  @override
  void dispose() {
    _circleAnimation.dispose();
    _lockAnimation.dispose();
    _unlockAnimation.dispose();
    super.dispose();
  }
}

/// Matches Android's AccelerateDecelerateInterpolator used by the old view.
class _AndroidFocusCurve extends Curve {
  const _AndroidFocusCurve();

  @override
  double transformInternal(double t) => t == .5 ? .5 : (1 - math.cos(math.pi * t)) / 2;
}

/// Draws the recognition mask and corner strokes in widget coordinates.
class _OverlayPainter extends CustomPainter {
  /// Normalized recognition rectangle relative to the current viewport.
  final CropRect crop;

  /// Chooses active or idle crop-corner color.
  final bool scanning;

  _OverlayPainter(this.crop, this.scanning);

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width * (1 + crop.offsetX) / 2, size.height * (1 + crop.offsetY) / 2);
    final area = Rect.fromCenter(center: center, width: size.width * crop.scaleWidth, height: size.height * crop.scaleHeight);
    canvas.save();
    canvas.clipRect(Offset.zero & size);
    final mask =
        Path()
          ..fillType = PathFillType.evenOdd
          ..addRect(Offset.zero & size)
          ..addRect(area);
    canvas.drawPath(mask, Paint()..color = const Color(0x78000000));
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

  @override
  bool shouldRepaint(_OverlayPainter old) => old.crop != crop || old.scanning != scanning;
}

/// Paints one frame of focus feedback without changing its animation state.
class _FocusPainter extends CustomPainter {
  /// Recognition geometry whose center anchors the circle and initial lock.
  final CropRect crop;

  /// Focus circle radius in logical pixels, matching the native platform.
  final double radius;

  /// Top and left inset of the settled 24-pixel lock icon.
  final double lockOffset;

  /// Horizontal distance from focus center to the lock's initial center.
  final double lockDistance;

  /// Current circle alpha, independent of the focus lock state.
  final double circleOpacity;

  /// Combined appearance and unlock alpha for the lock icon.
  final double lockOpacity;

  /// Progress from the focus point to the upper-left preview corner.
  final double lockTravel;

  _FocusPainter({
    required this.crop,
    required this.radius,
    required this.lockOffset,
    required this.lockDistance,
    required this.circleOpacity,
    required this.lockOpacity,
    required this.lockTravel,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width * (1 + crop.offsetX) / 2, size.height * (1 + crop.offsetY) / 2);
    canvas.save();
    canvas.clipRect(Offset.zero & size);
    if (circleOpacity > 0) {
      canvas.drawCircle(
        center,
        radius,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1
          ..color = Colors.white.withAlpha((255 * circleOpacity).round()),
      );
    }
    if (lockOpacity > 0) {
      final origin = Offset.lerp(center.translate(-lockDistance - 12, -12), Offset(lockOffset, lockOffset), lockTravel)!;
      canvas.translate(origin.dx, origin.dy);
      canvas.drawPath(_lockPath, Paint()..color = Colors.white.withAlpha((255 * lockOpacity).round()));
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(_FocusPainter old) =>
      old.crop != crop ||
      old.radius != radius ||
      old.lockOffset != lockOffset ||
      old.lockDistance != lockDistance ||
      old.circleOpacity != circleOpacity ||
      old.lockOpacity != lockOpacity ||
      old.lockTravel != lockTravel;

  /// Lock outline in its 24 x 24 icon coordinates.
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
}
