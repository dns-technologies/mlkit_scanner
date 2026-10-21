import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/exceptions/camera_exception.dart';

void main() {
  group('$CameraException', () {
    test('describes a camera failure using the native message without an operation tag', () {
      final exception = PlatformException(code: '9', message: 'Failed to set camera zoom ratio to 2.0', details: const {'viewId': 42});

      final result = CameraException.fromPlatformException(exception);

      expect(result.toString(), contains('Failed to set camera zoom ratio to 2.0'));
      expect(result.toString(), isNot(contains('operation:')));
    });

    group('$CameraExceptionCause', () {
      test('decodes well-formed details and formats its message', () {
        final cause = CameraExceptionCause.fromDetails(const {'type': 'NativeError', 'message': 'failed', 'stackTrace': 'trace'});

        expect(cause?.type, 'NativeError');
        expect(cause?.message, 'failed');
        expect(cause?.stackTrace, 'trace');
        expect(cause.toString(), 'NativeError: failed');
      });

      test('ignores malformed optional fields and rejects a missing type', () {
        final cause = CameraExceptionCause.fromDetails(const {'type': 'NativeError', 'message': 1, 'stackTrace': false});

        expect(cause?.message, isNull);
        expect(cause?.stackTrace, isNull);
        expect(cause.toString(), 'NativeError');
        expect(CameraExceptionCause.fromDetails(const {'message': 'failed'}), isNull);
        expect(CameraExceptionCause.fromDetails('failed'), isNull);
      });
    });

    test('preserves valid channel details and original exception metadata', () {
      final exception = PlatformException(
        code: CameraException.errorCode,
        message: 'Camera failed to open',
        details: const {
          'viewId': 42,
          'cameraStateErrorCode': 4,
          'cause': {'type': 'NativeError', 'message': 'disconnected'},
        },
        stacktrace: 'dart trace',
      );

      final result = CameraException.fromPlatformException(exception);

      expect(result.viewId, 42);
      expect(result.cameraStateErrorCode, 4);
      expect(result.cause?.type, 'NativeError');
      expect(result.message, exception.message);
      expect(result.details, same(exception.details));
      expect(result.stacktrace, exception.stacktrace);
      expect(
        result.toString(),
        'CameraException(code: 9, message: Camera failed to open, viewId: 42, '
        'cameraStateErrorCode: 4, cause: NativeError: disconnected)',
      );
    });

    test('uses safe defaults for malformed details', () {
      final result = CameraException.fromPlatformException(PlatformException(code: '9', details: 'invalid'));

      expect(result.viewId, isNull);
      expect(result.cameraStateErrorCode, isNull);
      expect(result.cause, isNull);
      expect(result.toString(), 'CameraException(code: 9)');
    });
  });
}
