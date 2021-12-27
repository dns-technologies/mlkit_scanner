//
//  MlKitPluginError.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 04.03.2021.
//

import Foundation

/// Errors of the MLkit Scanner Plugin
enum MlKitPluginError: String, Error {
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
}
