import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';
import 'package:mlkit_scanner/widgets/scanner_overlay.dart';

void main() {
  for (final reduceMotion in [false, true]) {
    testWidgets('Android and iOS paint identical focus feedback with reduced motion $reduceMotion', (tester) async {
      final android = await _feedbackFrames(tester, TargetPlatform.android, reduceMotion: reduceMotion);

      final ios = await _feedbackFrames(tester, TargetPlatform.iOS, reduceMotion: reduceMotion);

      expect(ios.length, android.length);
      for (var index = 0; index < android.length; index++) {
        expect(listEquals(ios[index], android[index]), isTrue, reason: 'Focus feedback differs at frame $index');
      }
      expect(listEquals(android.first, android[7]), isFalse, reason: 'The capture must include visible focus feedback');
    });
  }
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
