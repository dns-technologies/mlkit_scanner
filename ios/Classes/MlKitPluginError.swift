//
//  MlKitPluginError.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 04.03.2021.
//

import Foundation

/// Errors exposed by the scanner plugin.
enum MlKitPluginError: String, Error, LocalizedError {
    /// Can't initialize camera preview.
    case initCameraError = "1"
    /// Doesn't have permissions for using camera.
    case authorizationCameraError = "2"
    /// Can't use camera if it's not initialized.
    case cameraIsNotInitialized = "3"
    /// When device doesn't have a flash, or can't use it.
    case deviceHasNotFlash = "4"
    /// Malformed or incomplete command arguments.
    case invalidArguments = "5"

    /// The active camera does not support zoom control.
    case deviceHasNotZoom = "6"
    /// Unexpected internal scanner error.
    case unknownError = "7"
    /// The scanner session was released before an operation completed.
    case cameraSessionDisposed = "8"

    /// Human-readable description exposed through native error reporting.
    var errorDescription: String? {
        switch self {
        case .initCameraError:
            return "Internal camera initialisation error"
        case .authorizationCameraError:
            return "The app does not have camera permission"
        case .cameraIsNotInitialized:
            return "Camera platform view is not created"
        case .deviceHasNotFlash:
            return "Device has no flash"
        case .invalidArguments:
            return "Invalid scanner arguments"
        case .deviceHasNotZoom:
            return "Zoom is not supported on this device"
        case .unknownError:
            return "Unknown scanner error"
        case .cameraSessionDisposed:
            return "Camera session has been disposed"
        }
    }
}

/// Camera failure described where it occurs, with optional native diagnostic context.
struct CameraError: Error, LocalizedError {
    /// Stable platform-channel code for camera failures.
    static let errorCode = "9"

    /// Description of the camera failure.
    let message: String

    /// Camera consumer identifier associated with the failure, when available.
    let viewId: Int64?

    /// Original native failure retained for diagnostics.
    let underlyingError: Error?

    /// Native stack captured when this failure is reported.
    let stackTrace: String

    /// Optional native camera-state code included in channel diagnostics.
    let cameraStateErrorCode: Int?

    /// Human-readable description exposed through native error reporting.
    var errorDescription: String? {
        message
    }

    /// StandardMessageCodec-compatible diagnostic context.
    var channelDetails: [String: Any] {
        var details: [String: Any] = [:]
        if let viewId = viewId {
            details["viewId"] = viewId
        }
        if let cameraStateErrorCode = cameraStateErrorCode {
            details["cameraStateErrorCode"] = cameraStateErrorCode
        }
        if let underlyingError = underlyingError {
            details["cause"] = [
                "type": String(reflecting: type(of: underlyingError)),
                "message": underlyingError.localizedDescription,
                "stackTrace": stackTrace,
            ]
        }
        return details
    }

    init(
        message: String,
        viewId: Int64? = nil,
        underlyingError: Error? = nil,
        cameraStateErrorCode: Int? = nil,
        stackTrace: String = Thread.callStackSymbols.joined(separator: "\n")
    ) {
        self.message = message
        self.viewId = viewId
        self.underlyingError = underlyingError
        self.cameraStateErrorCode = cameraStateErrorCode
        self.stackTrace = stackTrace
    }

    /// Adds consumer context without replacing the original description or native failure.
    func contextualize(viewId: Int64?) -> CameraError {
        CameraError(message: message, viewId: viewId, underlyingError: underlyingError,
            cameraStateErrorCode: cameraStateErrorCode, stackTrace: stackTrace)
    }
}
