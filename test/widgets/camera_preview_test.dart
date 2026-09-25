import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/platform/scanner_preview.dart';
import 'package:mlkit_scanner/widgets/camera_preview.dart';

void main() {
  testWidgets('cold and starting outputs fill the preview area with solid black', (tester) async {
    for (final description in [
      null,
      const ScannerPreviewDescription(textureId: 9, size: Size(1280, 720), status: ScannerPreviewStatus.starting),
    ]) {
      await tester.pumpWidget(
        MaterialApp(home: Center(child: SizedBox(width: 240, height: 180, child: CameraPreview(description: description)))),
      );
      final placeholder = find.byWidgetPredicate((widget) => widget is ColoredBox && widget.color == Colors.black);
      expect(placeholder, findsOneWidget);
      expect(tester.getSize(placeholder), const Size(240, 180));
      expect(find.byType(CircularProgressIndicator), findsNothing);
      expect(find.byType(Texture), findsNothing);
    }
  });
  testWidgets('streaming and paused outputs share the same texture handle', (tester) async {
    for (final status in [ScannerPreviewStatus.streaming, ScannerPreviewStatus.paused]) {
      await tester.pumpWidget(
        MaterialApp(home: CameraPreview(description: ScannerPreviewDescription(textureId: 9, size: const Size(1280, 720), status: status))),
      );
      final texture = tester.widget<Texture>(find.byType(Texture));
      expect(texture.textureId, 9);
      expect(texture.freeze, status == ScannerPreviewStatus.paused);
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
