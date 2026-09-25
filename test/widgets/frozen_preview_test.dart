import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/src/widgets/frozen_preview.dart';

void main() {
  final frameKey = GlobalKey<FrozenPreviewState>();

  Widget preview({
    required Color color,
    bool paused = false,
    bool ready = true,
    bool hidden = false,
    double width = 40,
    Widget? child,
    bool Function()? canRetainFrame,
  }) => MaterialApp(
    home: Center(
      child: SizedBox(
        width: width,
        height: 40,
        child: Offstage(
          offstage: hidden,
          child: FrozenPreview(
            key: frameKey,
            paused: paused,
            ready: ready,
            canRetainFrame: canRetainFrame,
            onError: (error, stack) => Error.throwWithStackTrace(error, stack),
            child: child ?? ColoredBox(color: color),
          ),
        ),
      ),
    ),
  );

  Future<ui.Image> frozenImage(WidgetTester tester) async {
    await tester.runAsync(() => frameKey.currentState!.retainFrame());
    await tester.pump();
    return tester.widget<RawImage>(find.byType(RawImage)).image!;
  }

  Future<void> expectRed(WidgetTester tester, ui.Image image) async {
    final bytes = await tester.runAsync(() => image.toByteData());
    expect(bytes!.buffer.asUint8List().take(4), [244, 67, 54, 255]);
  }

  testWidgets('pause retains painted pixels before replacing the live child', (tester) async {
    await tester.pumpWidget(preview(color: Colors.red));
    await tester.pumpWidget(preview(color: Colors.blue, paused: true));
    final image = await frozenImage(tester);
    await expectRed(tester, image);

    await tester.pumpWidget(preview(color: Colors.green, paused: true, hidden: true));
    await tester.pumpWidget(preview(color: Colors.green, paused: true, width: 80));
    expect(await frozenImage(tester), same(image));
    await expectRed(tester, image);

    await tester.pumpWidget(preview(color: Colors.blue));
    expect(find.byType(RawImage), findsNothing);
    expect(image.debugDisposed, isTrue);
    await tester.pumpWidget(preview(color: Colors.blue, paused: true));
    final nextImage = await frozenImage(tester);
    final bytes = await tester.runAsync(() => nextImage.toByteData());
    expect(bytes!.buffer.asUint8List().take(4), [33, 150, 243, 255]);
    await tester.pumpWidget(const SizedBox());
    expect(nextImage.debugDisposed, isTrue);
  });

  testWidgets('a retained frame survives lost output until resume', (tester) async {
    await tester.pumpWidget(preview(color: Colors.red));
    await tester.pumpWidget(preview(color: Colors.red, paused: true));
    final image = await frozenImage(tester);
    await tester.pumpWidget(preview(color: Colors.grey, paused: true, ready: false));
    expect(tester.widget<RawImage>(find.byType(RawImage)).image, same(image));
    await expectRed(tester, image);
  });

  testWidgets('initial pause waits for camera pixels instead of copying loading', (tester) async {
    await tester.pumpWidget(preview(color: Colors.grey, paused: true, ready: false));
    expect(find.byType(RawImage), findsNothing);
    await tester.pumpWidget(preview(color: Colors.red, paused: true));
    await expectRed(tester, await frozenImage(tester));
  });

  testWidgets('handoff before the first camera paint never snapshots loading', (tester) async {
    await tester.pumpWidget(preview(color: Colors.grey, ready: false));
    late Future<void> retention;
    await tester.pumpWidget(
      preview(
        color: Colors.red,
        child: LayoutBuilder(
          builder: (context, constraints) {
            retention = frameKey.currentState!.retainFrame();
            return const ColoredBox(color: Colors.red);
          },
        ),
      ),
    );
    await tester.runAsync(() => retention);
    await tester.pump();
    expect(find.byType(RawImage), findsNothing);
    await tester.pumpWidget(preview(color: Colors.red, paused: true));
    await expectRed(tester, await frozenImage(tester));
  });

  testWidgets('deferred first snapshot cannot read the next owner frame', (tester) async {
    var ownsCamera = true;
    await tester.pumpWidget(
      preview(
        color: Colors.blue,
        paused: true,
        canRetainFrame: () => ownsCamera,
        child: LayoutBuilder(
          builder: (context, constraints) {
            ownsCamera = false;
            return const ColoredBox(color: Colors.blue);
          },
        ),
      ),
    );
    await tester.runAsync(() => frameKey.currentState!.retainFrame());
    await tester.pump();
    expect(find.byType(RawImage), findsNothing);
  });

  for (final dispose in [false, true]) {
    testWidgets('late snapshot is discarded after ${dispose ? 'dispose' : 'resume'}', (tester) async {
      await tester.pumpWidget(preview(color: Colors.red));
      final retention = frameKey.currentState!.retainFrame();
      await tester.pumpWidget(dispose ? const SizedBox() : preview(color: Colors.blue));
      await tester.runAsync(() => retention);
      await tester.pump();
      expect(find.byType(RawImage), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }
}
