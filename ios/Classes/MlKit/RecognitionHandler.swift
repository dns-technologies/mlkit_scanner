//
//  RecognitionHandler.swift
//
//  Created by ООО "ДНС Технологии" on 10.03.2021.
//

import Foundation
import AVFoundation

/// Processes camera frames for recognition.
protocol RecognitionHandler: AnyObject {
    /// Processes one physically oriented camera frame using the preview viewport.
    func processVideoOutput(sampleBuffer: CMSampleBuffer, viewport: CGSize)

}
