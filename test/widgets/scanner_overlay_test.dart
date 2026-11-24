import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';
import 'package:mlkit_scanner/widgets/scanner_overlay.dart';

void main() => group('$ScannerOverlay', () {
  testWidgets('draws the dimming mask as four bands outside the crop', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Center(
          child: SizedBox(
            width: 200,
            height: 100,
            child: ScannerOverlay(
              crop: const CropRect(scaleWidth: .5, scaleHeight: .5, offsetX: .25, offsetY: -.25),
              scanning: false,
              focusEnabled: false,
              onFocus: () {},
              onLockFocus: () {},
            ),
          ),
        ),
      ),
    );

    final overlay = find.descendant(of: find.byType(ScannerOverlay), matching: find.byType(CustomPaint));
    const dimming = Color(0x78000000);
    expect(
      overlay,
      paints
        ..rect(rect: const Rect.fromLTRB(0, 0, 75, 100), color: dimming)
        ..rect(rect: const Rect.fromLTRB(175, 0, 200, 100), color: dimming)
        ..rect(rect: const Rect.fromLTRB(75, 0, 175, 12.5), color: dimming)
        ..rect(rect: const Rect.fromLTRB(75, 62.5, 175, 100), color: dimming),
    );
  }, variant: TargetPlatformVariant.all());

  for (final size in [const Size(300, 400), const Size(400, 800)]) {
    testWidgets('the lock appears beside the circle without overlap at size $size', (tester) async {
      const crop = CropRect(scaleWidth: .8, scaleHeight: .6, offsetX: .2, offsetY: -.2);
      await tester.binding.setSurfaceSize(size);
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(
        MaterialApp(
          home: Center(
            child: SizedBox.fromSize(
              size: size,
              child: RepaintBoundary(
                child: ScannerOverlay(crop: crop, scanning: true, focusEnabled: true, onFocus: () {}, onLockFocus: () {}),
              ),
            ),
          ),
        ),
      );

      final overlay = find.byType(ScannerOverlay);
      await tester.longPress(overlay);
      await tester.pump(const Duration(milliseconds: 250));

      final components = await tester.runAsync(() async {
        final image = await captureImage(tester.element(overlay));
        try {
          final data = (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!;
          return _whiteComponents(data.buffer.asUint8List(), image.width, image.height);
        } finally {
          image.dispose();
        }
      });
      final center = Offset(size.width * (1 + crop.offsetX) / 2, size.height * (1 + crop.offsetY) / 2);
      final circle = components!.singleWhere((rect) => rect.contains(center));
      final lock = components.where((rect) => rect != circle).fold<Rect?>(null, (bounds, rect) => bounds?.expandToInclude(rect) ?? rect);
      expect(lock, isNotNull, reason: 'The circle and lock should be separate shapes');
      expect(lock!.right, lessThan(circle.left - 8));
      expect(lock.center.dy, closeTo(circle.center.dy, 2));
      await tester.pumpWidget(const SizedBox());
    });
  }

  for (final reduceMotion in [false, true]) {
    testWidgets('Android and iOS paint identical focus feedback with reduced motion $reduceMotion', (tester) async {
      final previousPlatform = debugDefaultTargetPlatformOverride;
      late final List<Uint8List> android;
      late final List<Uint8List> ios;
      try {
        debugDefaultTargetPlatformOverride = TargetPlatform.android;
        android = await _feedbackFrames(tester, TargetPlatform.android, reduceMotion: reduceMotion);
        debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
        ios = await _feedbackFrames(tester, TargetPlatform.iOS, reduceMotion: reduceMotion);
      } finally {
        debugDefaultTargetPlatformOverride = previousPlatform;
      }

      expect(ios.length, android.length);
      for (var index = 0; index < android.length; index++) {
        expect(listEquals(ios[index], android[index]), isTrue, reason: 'Focus feedback differs at frame $index');
      }
      expect(listEquals(android.first, android[7]), isFalse, reason: 'The capture must include visible focus feedback');
    });
  }
});

List<Rect> _whiteComponents(Uint8List pixels, int width, int height) {
  final remaining = <int>{
    for (var index = 0; index < width * height; index++)
      if (pixels[index * 4] > 0 && pixels[index * 4] == pixels[index * 4 + 1] && pixels[index * 4] == pixels[index * 4 + 2]) index,
  };
  final bounds = <Rect>[];
  while (remaining.isNotEmpty) {
    final pending = [remaining.first];
    remaining.remove(pending.first);
    var rect = Rect.fromLTWH((pending.first % width).toDouble(), (pending.first ~/ width).toDouble(), 1, 1);
    while (pending.isNotEmpty) {
      final index = pending.removeLast();
      final x = index % width;
      final y = index ~/ width;
      rect = rect.expandToInclude(Rect.fromLTWH(x.toDouble(), y.toDouble(), 1, 1));
      for (var dy = -1; dy <= 1; dy++) {
        for (var dx = -1; dx <= 1; dx++) {
          if (x + dx < 0 || x + dx >= width || y + dy < 0 || y + dy >= height) continue;
          final neighbor = (y + dy) * width + x + dx;
          if (remaining.remove(neighbor)) pending.add(neighbor);
        }
      }
    }
    bounds.add(rect);
  }
  return bounds;
}

Future<List<Uint8List>> _feedbackFrames(WidgetTester tester, TargetPlatform platform, {required bool reduceMotion}) async {
  await tester.pumpWidget(
    MaterialApp(
      key: ValueKey(platform),
      theme: ThemeData(platform: platform),
      builder: (context, child) => MediaQuery(data: MediaQuery.of(context).copyWith(disableAnimations: reduceMotion), child: child!),
      home: Center(
        child: SizedBox(
          width: 300,
          height: 400,
          child: RepaintBoundary(
            child: ScannerOverlay(
              crop: const CropRect(scaleWidth: .8, scaleHeight: .6, offsetX: .2, offsetY: -.2),
              scanning: true,
              focusEnabled: true,
              onFocus: () {},
              onLockFocus: () {},
            ),
          ),
        ),
      ),
    ),
  );
  final overlay = find.byType(ScannerOverlay);
  final frames = <Uint8List>[];

  Future<void> capture(List<int> intervals) async {
    for (final milliseconds in intervals) {
      await tester.pump(Duration(milliseconds: milliseconds));
      final pixels = await tester.runAsync(() async {
        final image = await captureImage(tester.element(overlay));
        try {
          final data = (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!;
          return Uint8List.fromList(data.buffer.asUint8List());
        } finally {
          image.dispose();
        }
      });
      frames.add(pixels!);
    }
  }

  await tester.tap(overlay);
  await capture([0, 125, 125, 250, 500]);
  await tester.longPress(overlay);
  await capture([0, 125, 175, 250, 250, 200]);
  await tester.tap(overlay);
  await capture([0, 100, 100]);
  await tester.pumpWidget(const SizedBox());
  return frames;
}
