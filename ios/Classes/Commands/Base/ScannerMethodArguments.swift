import CoreGraphics
import Foundation

/// Validates untyped Flutter values once, before they reach the SDK.
enum ScannerMethodArguments {

    /// Parsed recognition configuration for a scan start.
    struct ScanOptions {
        /// Validated recognition mode requested by Flutter.
        let type: RecognitionType
        /// Requested successful-recognition cooldown in milliseconds.
        let delay: Int
    }

    /// Parses the platform-view identifier required when attaching a preview.
    static func viewId(_ arguments: Any?) throws -> Int64 {
        let values = try map(arguments)
        return try nonNegativeInt64(values[PluginConstants.viewIdArgument])
    }

    /// Parses recognition mode and cooldown validated by Dart.
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

    /// Parses a recognition cooldown; Dart owns its range.
    static func scanDelay(_ arguments: Any?) throws -> Int {
        let values = try map(arguments)
        return try integer(values[PluginConstants.delayArgument])
    }

    /// Reads numeric zoom; Dart validates its application-level range.
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
