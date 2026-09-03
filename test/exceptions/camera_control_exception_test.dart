import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/exceptions/camera_control_exception.dart';

void main() {
  group('$CameraControlException', () {
    test('decodes known values and falls back for unknown values', () {
      expect(
        CameraControlOperation.fromWireValue('zoom'),
        CameraControlOperation.zoom,
      );
      expect(
        CameraControlOperation.fromWireValue('futureOperation'),
        CameraControlOperation.unknown,
      );
      expect(
        CameraControlOperation.fromWireValue(null),
        CameraControlOperation.unknown,
      );
    });

    group('$CameraControlExceptionCause', () {
      test('decodes well-formed details and formats its message', () {
        final cause = CameraControlExceptionCause.fromDetails(const {
          'type': 'NativeError',
          'message': 'failed',
          'stackTrace': 'trace',
        });

        expect(cause?.type, 'NativeError');
        expect(cause?.message, 'failed');
        expect(cause?.stackTrace, 'trace');
        expect(cause.toString(), 'NativeError: failed');
      });

      test('ignores malformed optional fields and rejects a missing type', () {
        final cause = CameraControlExceptionCause.fromDetails(const {
          'type': 'NativeError',
          'message': 1,
          'stackTrace': false,
        });

        expect(cause?.message, isNull);
        expect(cause?.stackTrace, isNull);
        expect(cause.toString(), 'NativeError');
        expect(
          CameraControlExceptionCause.fromDetails(const {'message': 'failed'}),
          isNull,
        );
        expect(CameraControlExceptionCause.fromDetails('failed'), isNull);
      });
    });

    test('preserves valid channel details and original exception metadata', () {
      final exception = PlatformException(
        code: CameraControlException.errorCode,
        message: 'Camera control operation failed',
        details: const {
          'operation': 'awaitOpen',
          'viewId': 42,
          'cameraStateErrorCode': 4,
          'cause': {
            'type': 'NativeError',
            'message': 'disconnected',
          },
        },
        stacktrace: 'dart trace',
      );

      final result = CameraControlException.fromPlatformException(exception);

      expect(result.operation, CameraControlOperation.awaitOpen);
      expect(result.viewId, 42);
      expect(result.cameraStateErrorCode, 4);
      expect(result.cause?.type, 'NativeError');
      expect(result.message, exception.message);
      expect(result.details, same(exception.details));
      expect(result.stacktrace, exception.stacktrace);
      expect(
        result.toString(),
        'CameraControlException(code: 9, operation: awaitOpen, viewId: 42, '
        'cameraStateErrorCode: 4, cause: NativeError: disconnected)',
      );
    });

    test('uses safe defaults for malformed details', () {
      final result = CameraControlException.fromPlatformException(
        PlatformException(code: '9', details: 'invalid'),
      );

      expect(result.operation, CameraControlOperation.unknown);
      expect(result.viewId, isNull);
      expect(result.cameraStateErrorCode, isNull);
      expect(result.cause, isNull);
    });
  });
}
