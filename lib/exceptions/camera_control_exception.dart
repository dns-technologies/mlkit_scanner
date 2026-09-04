import 'package:flutter/services.dart';

/// Camera operation reported by native error code `9`.
enum CameraControlOperation {
  /// Waiting for the lifecycle-bound camera device to open.
  awaitOpen('awaitOpen'),

  /// Applying camera zoom.
  zoom('zoom'),

  /// Applying camera torch state.
  torch('torch'),

  /// Applying focus and metering.
  focus('focus'),

  /// An operation value not recognized by this plugin version.
  unknown('unknown');

  const CameraControlOperation(this.wireValue);

  /// Value transported through the platform channel.
  final String wireValue;

  /// Parses a native operation value without throwing on newer values.
  static CameraControlOperation fromWireValue(Object? value) {
    return CameraControlOperation.values.firstWhere(
      (operation) => operation.wireValue == value,
      orElse: () => CameraControlOperation.unknown,
    );
  }
}

/// Original native failure that caused a camera control operation to fail.
class CameraControlExceptionCause {
  /// Creates structured native failure information.
  const CameraControlExceptionCause({
    required this.type,
    this.message,
    this.stackTrace,
  });

  /// Fully qualified native exception type.
  final String type;

  /// Native exception message, when available.
  final String? message;

  /// Native stack trace captured when the camera operation failed.
  final String? stackTrace;

  /// Decodes native failure details or returns `null` for malformed data.
  static CameraControlExceptionCause? fromDetails(Object? value) {
    if (value is! Map) return null;
    final type = value['type'];
    if (type is! String) return null;
    final message = value['message'];
    final stackTrace = value['stackTrace'];
    return CameraControlExceptionCause(
      type: type,
      message: message is String ? message : null,
      stackTrace: stackTrace is String ? stackTrace : null,
    );
  }

  @override
  String toString() => message == null ? type : '$type: $message';
}

/// Typed form of native camera control error code `9`.
///
/// This remains a [PlatformException], so existing callers that catch
/// [PlatformException] continue to work.
class CameraControlException extends PlatformException {
  /// Creates a typed camera control exception.
  CameraControlException({
    required this.operation,
    required this.viewId,
    this.cause,
    this.cameraStateErrorCode,
    String? message,
    Object? details,
    String? stacktrace,
  }) : super(
          code: errorCode,
          message: message,
          details: details,
          stacktrace: stacktrace,
        );

  /// Converts a code `9` platform exception into its typed representation.
  factory CameraControlException.fromPlatformException(
    PlatformException exception,
  ) {
    final rawDetails = exception.details as Object?;
    final details = rawDetails is Map ? rawDetails : const <Object?, Object?>{};
    final rawViewId = details['viewId'];
    final rawCameraStateErrorCode = details['cameraStateErrorCode'];
    return CameraControlException(
      operation: CameraControlOperation.fromWireValue(details['operation']),
      viewId: rawViewId is int ? rawViewId : null,
      cause: CameraControlExceptionCause.fromDetails(details['cause']),
      cameraStateErrorCode:
          rawCameraStateErrorCode is int ? rawCameraStateErrorCode : null,
      message: exception.message,
      details: rawDetails,
      stacktrace: exception.stacktrace,
    );
  }

  /// Stable MethodChannel error code used by native camera control failures.
  static const errorCode = '9';

  /// Camera operation that failed.
  final CameraControlOperation operation;

  /// Flutter platform-view identifier associated with the failed operation.
  final int? viewId;

  /// Original native failure, when available.
  final CameraControlExceptionCause? cause;

  /// Native camera-state error code for [CameraControlOperation.awaitOpen].
  final int? cameraStateErrorCode;

  @override
  String toString() {
    final context = <String>[
      'operation: ${operation.wireValue}',
      if (viewId != null) 'viewId: $viewId',
      if (cameraStateErrorCode != null)
        'cameraStateErrorCode: $cameraStateErrorCode',
      if (cause != null) 'cause: $cause',
    ].join(', ');
    return 'CameraControlException(code: $code, $context)';
  }
}
