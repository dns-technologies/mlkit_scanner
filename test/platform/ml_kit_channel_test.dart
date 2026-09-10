import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/platform/ml_kit_channel.dart';

import '../support/runtime_harness.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late RuntimeHarness h;
  setUp(() => h = RuntimeHarness());
  tearDown(() => h.dispose());

  test('channel streams expose all view-tagged events before any capture',
      () async {
    final scanViews = <int>[];
    final torchViews = <int>[];
    addTearDown(MlKitChannel()
        .scanResults
        .listen((e) => scanViews.add(e.viewId))
        .cancel);
    addTearDown(MlKitChannel()
        .torchToggleStream
        .listen((e) => torchViews.add(e.viewId))
        .cancel);
    await h.event(1, 'first');
    await h.event(2, 'second');
    expect(scanViews, [1, 2]);
    expect(torchViews, [1, 2]);
  });
}
