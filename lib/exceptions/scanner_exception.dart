import 'package:flutter/services.dart';

/// A typed native scanner failure with a message and native stack trace.
///
/// Known codes select a specific subtype; unknown codes retain their value in an
/// [UnknownScannerException]. All subtypes remain [PlatformException]s.
class ScannerException extends PlatformException {
  ScannerException({required super.code, super.message, super.stacktrace});

  ScannerException._fromPlatformException(PlatformException exception)
    : this(code: exception.code, message: exception.message, stacktrace: _nativeStackTrace(exception));

  /// Converts every native error, including codes added by future versions.
  factory ScannerException.fromPlatformException(PlatformException exception) => switch (exception.code) {
    '1' => CameraInitializationException._(exception),
    '2' => CameraPermissionException._(exception),
    '3' => CameraNotInitializedException._(exception),
    '4' => FlashUnavailableException._(exception),
    '5' => InvalidArgumentsException._(exception),
    '6' => ZoomUnavailableException._(exception),
    '8' => CameraSessionDisposedException._(exception),
    '9' => CameraException._(exception),
    _ => UnknownScannerException._(exception),
  };

  @override
  String toString() {
    final context = <String>['code: $code', if (message != null) 'message: $message'].join(', ');
    return '$runtimeType($context)';
  }

  static String? _nativeStackTrace(PlatformException exception) {
    if (exception.stacktrace != null) return exception.stacktrace;
    final details = exception.details;
    final value = details is Map ? details['stacktrace'] : null;
    return value is String ? value : null;
  }
}

/// The native camera could not be initialized (channel code `1`).
class CameraInitializationException extends ScannerException {
  CameraInitializationException({super.message, super.stacktrace}) : super(code: '1');

  CameraInitializationException._(super.exception) : super._fromPlatformException();
}

/// Camera permission was denied (channel code `2`).
class CameraPermissionException extends ScannerException {
  CameraPermissionException({super.message, super.stacktrace}) : super(code: '2');

  CameraPermissionException._(super.exception) : super._fromPlatformException();
}

/// An operation requires a camera that is not initialized (channel code `3`).
class CameraNotInitializedException extends ScannerException {
  CameraNotInitializedException({super.message, super.stacktrace}) : super(code: '3');

  CameraNotInitializedException._(super.exception) : super._fromPlatformException();
}

/// The active camera has no usable flash (channel code `4`).
class FlashUnavailableException extends ScannerException {
  FlashUnavailableException({super.message, super.stacktrace}) : super(code: '4');

  FlashUnavailableException._(super.exception) : super._fromPlatformException();
}

/// Native command arguments are missing or invalid (channel code `5`).
class InvalidArgumentsException extends ScannerException {
  InvalidArgumentsException({super.message, super.stacktrace}) : super(code: '5');

  InvalidArgumentsException._(super.exception) : super._fromPlatformException();
}

/// The active camera does not support zoom control (channel code `6`).
class ZoomUnavailableException extends ScannerException {
  ZoomUnavailableException({super.message, super.stacktrace}) : super(code: '6');

  ZoomUnavailableException._(super.exception) : super._fromPlatformException();
}

/// An unexpected native failure (`7`) or an unrecognized channel code.
class UnknownScannerException extends ScannerException {
  UnknownScannerException({super.code = '7', super.message, super.stacktrace});

  UnknownScannerException._(super.exception) : super._fromPlatformException();
}

/// The camera session was disposed before the operation finished (channel code `8`).
class CameraSessionDisposedException extends ScannerException {
  CameraSessionDisposedException({super.message, super.stacktrace}) : super(code: '8');

  CameraSessionDisposedException._(super.exception) : super._fromPlatformException();
}

/// A camera operation failed (channel code `9`).
class CameraException extends ScannerException {
  CameraException({super.message, super.stacktrace}) : super(code: '9');

  CameraException._(super.exception) : super._fromPlatformException();
}
