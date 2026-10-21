import 'package:flutter/services.dart';

/// Original native failure that caused a camera error.
class CameraExceptionCause {
  const CameraExceptionCause({required this.type, this.message, this.stackTrace});

  /// Fully qualified native exception type.
  final String type;

  /// Native exception message, when available.
  final String? message;

  /// Native stack trace captured when the camera operation failed.
  final String? stackTrace;

  /// Decodes native failure details or returns `null` for malformed data.
  static CameraExceptionCause? fromDetails(Object? value) {
    if (value is! Map) return null;
    final type = value['type'];
    if (type is! String) return null;
    final message = value['message'];
    final stackTrace = value['stackTrace'];
    return CameraExceptionCause(
      type: type,
      message: message is String ? message : null,
      stackTrace: stackTrace is String ? stackTrace : null,
    );
  }

  @override
  String toString() => message == null ? type : '$type: $message';
}

/// Camera failure with a descriptive native message and optional diagnostic context.
///
/// This remains a [PlatformException], so existing callers that catch
/// [PlatformException] continue to work.
class CameraException extends PlatformException {
  CameraException({this.viewId, this.cause, this.cameraStateErrorCode, super.message, Object? super.details, super.stacktrace})
    : super(code: errorCode);

  factory CameraException.fromPlatformException(PlatformException exception) {
    final rawDetails = exception.details as Object?;
    final details = rawDetails is Map ? rawDetails : const <Object?, Object?>{};
    final rawViewId = details['viewId'];
    final rawCameraStateErrorCode = details['cameraStateErrorCode'];
    return CameraException(
      viewId: rawViewId is int ? rawViewId : null,
      cause: CameraExceptionCause.fromDetails(details['cause']),
      cameraStateErrorCode: rawCameraStateErrorCode is int ? rawCameraStateErrorCode : null,
      message: exception.message,
      details: rawDetails,
      stacktrace: exception.stacktrace,
    );
  }

  /// Stable MethodChannel error code used by native camera failures.
  static const errorCode = '9';

  /// Logical consumer identifier associated with the failed operation.
  final int? viewId;

  /// Original native failure, when available.
  final CameraExceptionCause? cause;

  /// Native camera-state error code when camera readiness failed.
  final int? cameraStateErrorCode;

  @override
  String toString() {
    final context = <String>[
      'code: $code',
      if (message != null) 'message: $message',
      if (viewId != null) 'viewId: $viewId',
      if (cameraStateErrorCode != null) 'cameraStateErrorCode: $cameraStateErrorCode',
      if (cause != null) 'cause: $cause',
    ].join(', ');
    return 'CameraException($context)';
  }
}
