import CoreGraphics
import Foundation

/// Decodes typed scanner command arguments from untyped channel values.
enum ScannerMethodArguments {

    /// Parsed recognition configuration for a scan start.
    struct ScanOptions {
        /// Requested recognition mode.
        let type: RecognitionType
        /// Requested successful-recognition cooldown in milliseconds.
        let delay: Int
    }

    /// Parses a nonnegative consumer identifier.
    static func viewId(_ arguments: Any?) throws -> Int64 {
        let values = try map(arguments)
        return try nonNegativeInt64(values[PluginConstants.viewIdArgument])
    }

    /// Parses the recognition mode and integer cooldown.
    static func scanOptions(_ arguments: Any?) throws -> ScanOptions {
        let values = try map(arguments)
        let rawType = try integer(values[PluginConstants.typeArgument])
        guard let type = RecognitionType(rawValue: rawType) else {
            throw MlKitPluginError.invalidArguments
        }
        return ScanOptions(
            type: type,
            delay: try integer(values[PluginConstants.delayArgument])
        )
    }

    /// Parses a recognition cooldown as an exact integer.
    static func scanDelay(_ arguments: Any?) throws -> Int {
        let values = try map(arguments)
        return try integer(values[PluginConstants.delayArgument])
    }

    /// Reads a finite numeric zoom ratio.
    static func zoomRatio(_ arguments: Any?) throws -> Double {
        let values = try map(arguments)
        return try PlatformChannelScalar.finiteDouble(from: values[PluginConstants.valueArgument])
    }

    /// Parses normalized crop geometry.
    static func cropRect(_ arguments: Any?) throws -> CropRect {
        let values = try map(arguments)
        let cropValues = try map(values[PluginConstants.cropRectArgument])
        return try CropRect(arguments: cropValues)
    }

    /// Decodes positive finite viewport dimensions.
    static func geometry(_ arguments: Any?) throws -> CGSize {
        let values = try map(arguments)
        let width = try PlatformChannelScalar.finiteDouble(from: values["width"])
        let height = try PlatformChannelScalar.finiteDouble(from: values["height"])
        guard width > 0, height > 0 else { throw MlKitPluginError.invalidArguments }
        return CGSize(width: width, height: height)
    }

    /// Returns an untyped channel value as a string-keyed map.
    static func map(_ value: Any?) throws -> [String: Any] {
        guard let map = value as? [String: Any] else {
            throw MlKitPluginError.invalidArguments
        }
        return map
    }

    /// Converts a channel number to `Int` without truncation or overflow.
    private static func integer(_ value: Any?) throws -> Int {
        let number = try PlatformChannelScalar.number(from: value)
        let result = number.intValue
        guard number.compare(NSNumber(value: result)) == .orderedSame else {
            throw MlKitPluginError.invalidArguments
        }
        return result
    }

    /// Converts an exact, nonnegative channel number to `Int64`.
    private static func nonNegativeInt64(_ value: Any?) throws -> Int64 {
        let number = try PlatformChannelScalar.number(from: value)
        let result = number.int64Value
        guard
            result >= 0,
            number.compare(NSNumber(value: result)) == .orderedSame
        else {
            throw MlKitPluginError.invalidArguments
        }
        return result
    }
}
