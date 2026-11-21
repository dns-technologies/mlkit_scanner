import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';

void main() {
  final categories = <String, Type>{
    '1': CameraInitializationException,
    '2': CameraPermissionException,
    '3': CameraNotInitializedException,
    '4': FlashUnavailableException,
    '5': InvalidArgumentsException,
    '6': ZoomUnavailableException,
    '7': UnknownScannerException,
    '8': CameraSessionDisposedException,
    '9': CameraException,
    'future-native-code': UnknownScannerException,
    '': UnknownScannerException,
  };

  for (final entry in categories.entries) {
    test('native code ${entry.key} becomes ${entry.value} without losing diagnostics', () {
      final source = PlatformException(code: entry.key, message: 'Native operation failed', details: const {'stacktrace': 'native trace'});

      final result = ScannerException.fromPlatformException(source);

      expect(result.runtimeType, entry.value);
      expect(result, isA<PlatformException>());
      expect(result.code, source.code);
      expect(result.message, source.message);
      expect(result.details, isNull);
      expect(result.stacktrace, 'native trace');
    });
  }

  for (final details in [
    null,
    'malformed',
    7,
    const {'stacktrace': false},
  ]) {
    test('unknown error tolerates details $details', () {
      final source = PlatformException(code: 'unrecognized', details: details);

      final result = ScannerException.fromPlatformException(source);

      expect(result, isA<UnknownScannerException>());
      expect(result.code, 'unrecognized');
      expect(result.details, isNull);
      expect(result.stacktrace, isNull);
      expect(result.toString(), 'UnknownScannerException(code: unrecognized)');
    });
  }

  test('preserves an explicit channel stack trace', () {
    final source = PlatformException(
      code: '9',
      message: 'Camera closed',
      details: const {'stacktrace': 'fallback trace'},
      stacktrace: 'channel trace',
    );

    final result = ScannerException.fromPlatformException(source);

    expect(result, isA<CameraException>());
    expect(result.stacktrace, 'channel trace');
    expect(result.toString(), 'CameraException(code: 9, message: Camera closed)');
  });
}
