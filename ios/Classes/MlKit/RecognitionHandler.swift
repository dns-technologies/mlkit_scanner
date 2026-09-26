//
//  RecognitionHandler.swift
//
//  Created by ООО "ДНС Технологии" on 10.03.2021.
//

import Foundation
import AVFoundation

/// Processes camera frames independently of view ownership.
protocol RecognitionHandler: AnyObject {
    /// Recognition mode implemented by this handler.
    var type: RecognitionType { get }

    /// Processes one physically oriented camera frame using the preview viewport.
    func processVideoOutput(sampleBuffer: CMSampleBuffer, viewport: CGSize)

}
