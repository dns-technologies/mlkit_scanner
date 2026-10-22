import CoreFoundation
import CoreGraphics
import Foundation

/// Requires a string-keyed codec map.
func requireMap(_ value: Any?) throws -> [String: Any] {
    guard let values = value as? [String: Any] else { throw MlKitPluginError.invalidArguments }
    return values
}

/// Typed access to codec arguments without coercing strings or Boolean values to numbers.
extension Dictionary where Key == String, Value == Any {
    /// Reads an optional nested map, treating codec null as absent.
    func optionalMap(_ key: String) throws -> [String: Any]? {
        guard let value = self[key], !(value is NSNull) else { return nil }
        guard let values = value as? [String: Any] else { throw MlKitPluginError.invalidArguments }
        return values
    }

    /// Requires a nested map under the requested channel key.
    func requireMap(_ key: String) throws -> [String: Any] {
        guard let values = try optionalMap(key) else { throw MlKitPluginError.invalidArguments }
        return values
    }

    /// Requires a Boolean while rejecting numeric zero and one values.
    func requireBoolean(_ key: String) throws -> Bool {
        guard let number = self[key] as? NSNumber,
              CFGetTypeID(number) == CFBooleanGetTypeID() else { throw MlKitPluginError.invalidArguments }
        return number.boolValue
    }

    /// Reads an optional finite number, treating codec null as absent.
    func optionalFiniteDouble(_ key: String) throws -> Double? {
        guard let value = self[key], !(value is NSNull) else { return nil }
        return try requireNumber(value).doubleValue
    }

    /// Requires a finite number without imposing application-specific ranges.
    func requireFiniteDouble(_ key: String) throws -> Double {
        try requireNumber(self[key]).doubleValue
    }

    /// Requires an exactly representable Int without truncation or overflow.
    func requireInt(_ key: String) throws -> Int {
        guard let value = Int(exactly: try requireInt64(key)) else { throw MlKitPluginError.invalidArguments }
        return value
    }

    /// Requires an exact Int64 while preserving integer precision beyond Double's exact range.
    func requireInt64(_ key: String) throws -> Int64 {
        let number = try requireNumber(self[key])
        let value = number.int64Value
        guard number.compare(NSNumber(value: value)) == .orderedSame else { throw MlKitPluginError.invalidArguments }
        return value
    }

    /// Decodes positive finite viewport dimensions, preserving subpixel sizes.
    func requireSize() throws -> CGSize {
        let width = try requireFiniteDouble("width")
        let height = try requireFiniteDouble("height")
        guard width > 0, height > 0 else { throw MlKitPluginError.invalidArguments }
        return CGSize(width: width, height: height)
    }
}

/// Rejects codec Boolean values and non-finite numbers before numeric conversion.
private func requireNumber(_ value: Any?) throws -> NSNumber {
    guard let number = value as? NSNumber,
          CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue.isFinite else {
        throw MlKitPluginError.invalidArguments
    }
    return number
}
