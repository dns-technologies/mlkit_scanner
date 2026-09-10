import 'package:flutter_test/flutter_test.dart';

/// Keeps ownership/control tests independent of rendering. Frame timing has its own widget tests.
class ImmediateFrameTestBinding extends AutomatedTestWidgetsFlutterBinding {
  @override
  Future<void> get endOfFrame => Future<void>.value();
}
