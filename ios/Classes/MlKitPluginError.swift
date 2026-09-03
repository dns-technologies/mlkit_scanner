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
    /// Flutter side sends wrong argument
    case invalidArguments = "5"

    /// The active camera does not support zoom control.
    case deviceHasNotZoom = "6"
    /// Unexpected internal scanner error.
    case unknownError = "7"
    /// The scanner session was released before an operation completed.
    case cameraSessionDisposed = "8"

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

/// Camera operation attached to method-channel error code 9.
enum CameraControlOperation: String {
    case awaitOpen
    case zoom
    case torch
    case focus
}

/// Adds view and operation context to an underlying camera-control failure.
struct CameraControlError: Error, LocalizedError {
    static let errorCode = "9"
    static let errorMessage = "Camera control operation failed"

    let operation: CameraControlOperation
    let viewId: Int64?
    let underlyingError: Error?
    let cameraStateErrorCode: Int?

    init(
        operation: CameraControlOperation,
        viewId: Int64? = nil,
        underlyingError: Error? = nil,
        cameraStateErrorCode: Int? = nil
    ) {
        self.operation = operation
        self.viewId = viewId
        self.underlyingError = underlyingError
        self.cameraStateErrorCode = cameraStateErrorCode
    }

    var errorDescription: String? {
        Self.errorMessage
    }

    /// StandardMessageCodec-compatible diagnostic context.
    var channelDetails: [String: Any] {
        var details: [String: Any] = ["operation": operation.rawValue]
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
            ]
        }
        return details
    }
}
