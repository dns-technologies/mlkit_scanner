import CoreFoundation
import Foundation

/// Decodes scalar values produced by Flutter's Darwin standard message codec.
enum PlatformChannelScalar {

    /// Reads a finite numeric value without imposing scanner-specific ranges.
    static func finiteDouble(from value: Any?) throws -> Double {
        let value = try number(from: value).doubleValue
        guard value.isFinite else { throw MlKitPluginError.invalidArguments }
        return value
    }

    /// Returns a numeric `NSNumber` while rejecting codec Boolean values.
    static func number(from value: Any?) throws -> NSNumber {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID() else {
            throw MlKitPluginError.invalidArguments
        }
        return number
    }

    /// Returns a Boolean while rejecting numeric zero and one values.
    static func bool(from value: Any?) throws -> Bool {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) == CFBooleanGetTypeID() else {
            throw MlKitPluginError.invalidArguments
        }
        return number.boolValue
    }
}
