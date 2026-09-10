import 'dart:async';

import 'package:flutter_test/flutter_test.dart';

import '../support/runtime_harness.dart';

void main() {
  testWidgets('capture waits for a frame after queued zoom finishes', (tester) async {
    final h = RuntimeHarness();
    addTearDown(h.dispose);
    final controller = h.controller(1);
    await controller.setZoomRatio(2);
    final captured = Completer<void>();
    final zoomed = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'resumeCameraMethod') await captured.future;
      if (call.method == 'setZoomRatio') await zoomed.future;
      return null;
    };
    var completed = false;
    final capture = h.runtime.capture(controller).then((_) => completed = true);
    await tester.idle();
    expect((h.calls.single.arguments as Map)['configuration'], containsPair('zoomRatio', 2.0));

    await controller.setZoomRatio(4);
    captured.complete();
    await tester.pump();
    expect(h.methods, ['resumeCameraMethod', 'setZoomRatio']);
    expect(h.calls.last.arguments, {'value': 4.0});
    expect(controller.previewVisible.value, isFalse);

    zoomed.complete();
    await tester.idle();
    expect(completed, isFalse);
    expect(controller.previewVisible.value, isFalse);

    await tester.pump();
    await capture;
    expect(controller.previewVisible.value, isTrue);
  });

  testWidgets('release during the frame wait cannot reveal the old preview', (tester) async {
    final h = RuntimeHarness();
    addTearDown(h.dispose);
    final controller = h.controller(1);
    final capture = h.runtime.capture(controller);
    await tester.idle();
    expect(h.methods, ['resumeCameraMethod']);
    expect(controller.previewVisible.value, isFalse);

    await h.runtime.release(controller);
    await tester.pump();
    await capture;

    expect(controller.previewVisible.value, isFalse);
    expect(h.runtime.isCurrent(controller), isFalse);
  });

  testWidgets('a replaced capture cannot reveal preview on its pending frame', (tester) async {
    final h = RuntimeHarness();
    addTearDown(h.dispose);
    final first = h.controller(1);
    final second = h.controller(2);
    final firstCapture = h.runtime.capture(first);
    await tester.idle();
    final captured = Completer<void>();
    h.handler = (call) async {
      if (call.method == 'resumeCameraMethod') await captured.future;
      return null;
    };
    final secondCapture = h.runtime.capture(second);
    await tester.pump();
    await firstCapture;
    expect(first.previewVisible.value, isFalse);
    expect(second.previewVisible.value, isFalse);

    captured.complete();
    await tester.idle();
    expect(second.previewVisible.value, isFalse);
    await tester.pump();
    await secondCapture;
    expect(first.previewVisible.value, isFalse);
    expect(second.previewVisible.value, isTrue);
  });
}
