import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/platform/scanner_preview.dart';
import 'package:mlkit_scanner/widgets/camera_preview.dart';

void main() {
  testWidgets('unregistered, starting and stopped outputs hide stale pixels', (tester) async {
    for (final description in [
      null,
      const ScannerPreviewDescription(textureId: 9, size: Size(1280, 720), status: ScannerPreviewStatus.starting),
      const ScannerPreviewDescription(textureId: 9, size: Size(1280, 720), status: ScannerPreviewStatus.paused),
    ]) {
      await tester.pumpWidget(
        MaterialApp(home: Center(child: SizedBox(width: 240, height: 180, child: CameraPreview(description: description)))),
      );
      final cover = find.byWidgetPredicate((widget) => widget is ColoredBox && widget.color == Colors.black);
      expect(cover, findsOneWidget);
      expect(tester.getSize(cover), const Size(240, 180));
      expect(find.byType(Texture), findsNothing);
    }
  });
  testWidgets('manual pause without an own snapshot hides a stopped texture', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: CameraPreview(
          description: ScannerPreviewDescription(textureId: 9, size: Size(1280, 720), status: ScannerPreviewStatus.paused),
          paused: true,
        ),
      ),
    );
    expect(find.byWidgetPredicate((widget) => widget is ColoredBox && widget.color == Colors.black), findsOneWidget);
    expect(find.byType(Texture), findsNothing);
  });
  testWidgets('streaming output renders the registered texture and freezes on manual pause', (tester) async {
    for (final paused in [false, true]) {
      await tester.pumpWidget(
        MaterialApp(
          home: CameraPreview(
            description: const ScannerPreviewDescription(textureId: 9, size: Size(1280, 720), status: ScannerPreviewStatus.streaming),
            paused: paused,
          ),
        ),
      );
      expect(find.byType(Texture), findsOneWidget);
      final texture = tester.widget<Texture>(find.byType(Texture));
      expect(texture.textureId, 9);
      expect(texture.freeze, paused);
    }
  });
  testWidgets('source crop is applied before quarter-turn and cover scaling', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: CameraPreview(
          description: ScannerPreviewDescription(
            textureId: 7,
            size: Size(1280, 720),
            cropRect: Rect.fromLTWH(160, 0, 960, 720),
            rotationDegrees: 90,
            status: ScannerPreviewStatus.streaming,
          ),
        ),
      ),
    );
    expect(tester.widget<RotatedBox>(find.byType(RotatedBox)).quarterTurns, 1);
    final source = tester.widget<Positioned>(find.byType(Positioned));
    expect(source.left, -160);
    expect(source.width, 1280);
    expect(tester.widget<FittedBox>(find.byType(FittedBox)).fit, BoxFit.cover);
  });
}
