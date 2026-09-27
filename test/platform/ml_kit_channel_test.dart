import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/platform/ml_kit_channel.dart';

import '../support/runtime_harness.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late RuntimeHarness h;
  setUp(() => h = RuntimeHarness());
  tearDown(() => h.dispose());

  test('channel streams expose all view-tagged events before any capture', () async {
    final scanViews = <int>[];
    final torchViews = <int>[];
    expect(identical(MlKitChannel(), MlKitChannel()), isTrue);
    addTearDown(MlKitChannel().scanResults.listen((e) => scanViews.add(e.viewId)).cancel);
    addTearDown(MlKitChannel().torchToggleStream.listen((e) => torchViews.add(e.viewId)).cancel);
    await h.event(1, 'first');
    await h.event(2, 'second');
    expect(scanViews, [1, 2]);
    expect(torchViews, [1, 2]);
  });

  group('preview decoding', () {
    Map<String, Object> description(int id) => {
      'textureId': id,
      'width': 1280,
      'height': 720,
      'rotationDegrees': 90,
      'mirrored': false,
      'state': 'streaming',
    };

    test('invalid initial preview metadata releases its allocated endpoint', () async {
      h.handler = (call) async {
        if (call.method == 'subscribePreview') {
          return {'subscriptionId': 'invalid-preview', 'description': description(9)..['width'] = 0};
        }
        return null;
      };

      await expectLater(MlKitChannel().subscribePreview(), throwsFormatException);

      final cleanup = h.calls.where((call) => call.method == 'unsubscribePreview');
      expect(cleanup, hasLength(1));
      expect(cleanup.single.arguments, containsPair('subscriptionId', 'invalid-preview'));
    });
  });
}
