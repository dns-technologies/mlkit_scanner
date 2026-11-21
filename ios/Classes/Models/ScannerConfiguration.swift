import Foundation

/// Camera selection and settings for capture activation.
/// Argument decoding defaults to the full preview and rear wide-angle camera when omitted.
struct ScannerConfiguration: Equatable {
    /// Absolute zoom factor requested for this capture.
    var zoomRatio: Double
    /// Desired torch state for this capture.
    var torchEnabled: Bool
    /// Normalized recognition and focus area.
    var cropRect: CropRect
    /// Requested native camera type and position.
    var camera: CameraData

    init(arguments: Any?) throws {
        let values = try ArgumentUtils.requireMap(arguments)
        zoomRatio = try values.requireFiniteDouble("zoomRatio")
        torchEnabled = try values.requireBoolean("torchEnabled")
        cropRect = try CropRect(arguments: values.optionalMap("cropRect") ?? [:])
        if let cameraMap = try values.optionalMap("iosCamera") {
            camera = try CameraData(arguments: cameraMap)
        } else {
            camera = CameraData(type: .builtInWideAngleCamera, position: .back)
        }
    }
}
