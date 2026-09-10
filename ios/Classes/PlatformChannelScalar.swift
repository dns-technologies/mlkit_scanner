import CoreFoundation
import Foundation

/// Decodes scalar values produced by Flutter's Darwin standard message codec.
enum PlatformChannelScalar {
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
