import CoreGraphics
import Foundation

/// Typed configuration registered together with one Flutter platform view.
struct ScannerViewRegistration {
    /// Optional native preview size supplied by Flutter.
    let size: CGSize?
    /// Normalized zoom to apply before showing the preview.
    let initialZoom: Double?
    /// Initial retained torch request.
    let initialFlashEnabled: Bool?
    /// Initial retained recognition rectangle.
    let initialCropRect: CropRect?
    /// Initial retained camera selection.
    let initialCamera: CameraData?

    /// Registration with no explicit initial configuration.
    static let empty = ScannerViewRegistration(
        size: nil,
        initialZoom: nil,
        initialFlashEnabled: nil,
        initialCropRect: nil,
        initialCamera: nil
    )
}

/// Validates untyped Flutter values once, before they reach scanner state.
enum ScannerMethodArguments {
    /// One parsed value addressed to a platform view.
    struct ViewValue<T> {
        let viewId: Int64
        let value: T
    }

    /// Parsed recognition configuration for a scan start.
    struct ScanOptions {
        let viewId: Int64
        let type: RecognitionType
        let delay: Int
    }

    /// Parses platform-view creation arguments and optional initial controls.
    static func viewRegistration(_ arguments: Any?) throws -> ScannerViewRegistration {
        guard let arguments = arguments else { return .empty }
        let values = try map(arguments)

        let width = try optionalDouble(values[PluginConstants.widthArgument])
        let height = try optionalDouble(values[PluginConstants.heightArgument])
        let size: CGSize?
        if let width = width, let height = height, width >= 0, height >= 0 {
            size = CGSize(width: width, height: height)
        } else if width == nil, height == nil {
            size = nil
        } else {
            throw MlKitPluginError.invalidArguments
        }

        let zoom = try optionalDouble(values[PluginConstants.initialZoomArgument])
        if let zoom = zoom, !(0...1).contains(zoom) {
            throw MlKitPluginError.invalidArguments
        }

        let cropMap = try optionalMap(values[PluginConstants.initialCropRectArgument])
        let cameraMap = try optionalMap(values[PluginConstants.initialCameraArgument])
        return ScannerViewRegistration(
            size: size,
            initialZoom: zoom,
            initialFlashEnabled: try optionalBool(
                values[PluginConstants.initialFlashEnabledArgument]
            ),
            initialCropRect: try cropMap.map { try CropRect(arguments: $0) },
            initialCamera: try cameraMap.map { try CameraData(arguments: $0) }
        )
    }

    /// Parses the platform-view identifier required by view-scoped commands.
    static func viewId(_ arguments: Any?) throws -> Int64 {
        let values = try map(arguments)
        return try nonNegativeInt64(values[PluginConstants.viewIdArgument])
    }

    /// Parses the target view, recognition mode, and nonnegative cooldown.
    static func scanOptions(_ arguments: Any?) throws -> ScanOptions {
        let values = try map(arguments)
        let rawType = try nonNegativeInt(values[PluginConstants.typeArgument])
        guard let type = RecognitionType(rawValue: rawType) else {
            throw MlKitPluginError.invalidArguments
        }
        return ScanOptions(
            viewId: try nonNegativeInt64(values[PluginConstants.viewIdArgument]),
            type: type,
            delay: try nonNegativeInt(values[PluginConstants.delayArgument])
        )
    }

    /// Parses a nonnegative recognition cooldown addressed to one view.
    static func scanDelay(_ arguments: Any?) throws -> ViewValue<Int> {
        let values = try map(arguments)
        return ViewValue(
            viewId: try nonNegativeInt64(values[PluginConstants.viewIdArgument]),
            value: try nonNegativeInt(values[PluginConstants.delayArgument])
        )
    }

    /// Parses normalized zoom addressed to one view.
    static func zoom(_ arguments: Any?) throws -> ViewValue<Double> {
        let values = try map(arguments)
        let zoom = try finiteDouble(values[PluginConstants.valueArgument])
        guard (0...1).contains(zoom) else {
            throw MlKitPluginError.invalidArguments
        }
        return ViewValue(
            viewId: try nonNegativeInt64(values[PluginConstants.viewIdArgument]),
            value: zoom
        )
    }

    /// Parses normalized crop geometry addressed to one view.
    static func cropRect(_ arguments: Any?) throws -> ViewValue<CropRect> {
        let values = try map(arguments)
        let cropValues = try map(values[PluginConstants.cropRectArgument])
        return ViewValue(
            viewId: try nonNegativeInt64(values[PluginConstants.viewIdArgument]),
            value: try CropRect(arguments: cropValues)
        )
    }

    /// Parses an iOS camera selection addressed to one view.
    static func camera(_ arguments: Any?) throws -> ViewValue<CameraData> {
        let values = try map(arguments)
        return ViewValue(
            viewId: try nonNegativeInt64(values[PluginConstants.viewIdArgument]),
            value: try CameraData(arguments: values)
        )
    }

    /// Returns an untyped channel value as a string-keyed map.
    private static func map(_ value: Any?) throws -> [String: Any] {
        guard let map = value as? [String: Any] else {
            throw MlKitPluginError.invalidArguments
        }
        return map
    }

    /// Parses an optional nested map while rejecting another value type.
    private static func optionalMap(_ value: Any?) throws -> [String: Any]? {
        guard let value = value, !(value is NSNull) else { return nil }
        return try map(value)
    }

    /// Parses an optional Boolean channel value.
    private static func optionalBool(_ value: Any?) throws -> Bool? {
        guard let value = value, !(value is NSNull) else { return nil }
        guard let value = value as? Bool else {
            throw MlKitPluginError.invalidArguments
        }
        return value
    }

    /// Parses an optional finite numeric channel value.
    private static func optionalDouble(_ value: Any?) throws -> Double? {
        guard let value = value, !(value is NSNull) else { return nil }
        return try finiteDouble(value)
    }

    /// Converts a numeric channel value to a finite double.
    private static func finiteDouble(_ value: Any?) throws -> Double {
        guard !(value is Bool), let number = value as? NSNumber else {
            throw MlKitPluginError.invalidArguments
        }
        let result = number.doubleValue
        guard result.isFinite else {
            throw MlKitPluginError.invalidArguments
        }
        return result
    }

    /// Converts an exact, nonnegative channel number to `Int`.
    private static func nonNegativeInt(_ value: Any?) throws -> Int {
        let number = try nonNegativeInt64(value)
        guard number <= Int64(Int.max) else {
            throw MlKitPluginError.invalidArguments
        }
        return Int(number)
    }

    /// Converts an exact, nonnegative channel number to `Int64`.
    private static func nonNegativeInt64(_ value: Any?) throws -> Int64 {
        guard !(value is Bool), let number = value as? NSNumber else {
            throw MlKitPluginError.invalidArguments
        }
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
