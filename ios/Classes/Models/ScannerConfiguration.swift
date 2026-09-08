import Foundation

/// Transient capture arguments. Desired configuration lives exclusively in Dart.
struct ScannerConfiguration {
    let zoomRatio: Double
    let torchEnabled: Bool
    let cropRect: CropRect
    let scanEnabled: Bool
    let scanDelay: Int
    let camera: CameraData

    init(arguments: Any?) throws {
        guard let values = arguments as? [String: Any] else {
            throw MlKitPluginError.invalidArguments
        }
        let zoom = try PlatformChannelScalar.number(from: values["zoomRatio"]).doubleValue
        let delay = try PlatformChannelScalar.number(from: values["scanDelay"])
        guard zoom.isFinite, zoom > 0,
              delay.compare(NSNumber(value: delay.intValue)) == .orderedSame else {
            throw MlKitPluginError.invalidArguments
        }
        zoomRatio = zoom
        scanDelay = delay.intValue
        torchEnabled = try PlatformChannelScalar.bool(from: values["torchEnabled"])
        scanEnabled = try PlatformChannelScalar.bool(from: values["scanEnabled"])
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
        guard let map = value as? [String: Any] else { throw MlKitPluginError.invalidArguments }
        return map
    }
}
