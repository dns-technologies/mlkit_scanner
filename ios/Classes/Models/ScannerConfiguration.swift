import Foundation

/// Transient capture arguments. Desired configuration lives exclusively in Dart.
struct ScannerConfiguration {
    /// Absolute zoom factor requested for this capture.
    let zoomRatio: Double
    /// Desired torch state for this capture.
    let torchEnabled: Bool
    /// Normalized recognition and focus area.
    let cropRect: CropRect
    /// Requested native camera type and position.
    let camera: CameraData

    /// Decodes capture settings whose application-level ranges are validated by Dart.
    init(arguments: Any?) throws {
        let values = try ScannerMethodArguments.map(arguments)
        zoomRatio = try PlatformChannelScalar.finiteDouble(from: values["zoomRatio"])
        torchEnabled = try PlatformChannelScalar.bool(from: values["torchEnabled"])
        cropRect = try CropRect(arguments: Self.map(values["cropRect"]) ?? [:])
        if let cameraMap = try Self.map(values["iosCamera"]) {
            camera = try CameraData(arguments: cameraMap)
        } else {
            camera = CameraData(type: .builtInWideAngleCamera, position: .back)
        }
    }

    /// Null means the cross-platform default, not an unvalidated dictionary.
    private static func map(_ value: Any?) throws -> [String: Any]? {
        guard let value = value, !(value is NSNull) else { return nil }
        return try ScannerMethodArguments.map(value)
    }
}
