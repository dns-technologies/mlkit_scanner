import Foundation
import XCTest
@testable import mlkit_scanner

final class PlatformChannelScalarTests: XCTestCase {
    func testNumberAcceptsCodecNumericZeroAndOne() throws {
        XCTAssertEqual(
            try PlatformChannelScalar.number(from: NSNumber(value: Int32(0))).intValue,
            0
        )
        XCTAssertEqual(
            try PlatformChannelScalar.number(from: NSNumber(value: Int32(1))).intValue,
            1
        )
        XCTAssertEqual(
            try PlatformChannelScalar.number(from: NSNumber(value: 1.0)).doubleValue,
            1
        )
    }

    func testNumberRejectsCodecBooleanAndNonNumberValues() {
        assertInvalid { _ = try PlatformChannelScalar.number(from: NSNumber(value: true)) }
        assertInvalid { _ = try PlatformChannelScalar.number(from: "1") }
        assertInvalid { _ = try PlatformChannelScalar.number(from: nil) }
    }

    func testBoolAcceptsOnlyCoreFoundationBoolean() throws {
        XCTAssertTrue(try PlatformChannelScalar.bool(from: NSNumber(value: true)))
        XCTAssertFalse(try PlatformChannelScalar.bool(from: NSNumber(value: false)))
        assertInvalid { _ = try PlatformChannelScalar.bool(from: NSNumber(value: Int32(0))) }
        assertInvalid { _ = try PlatformChannelScalar.bool(from: NSNumber(value: Int32(1))) }
        assertInvalid { _ = try PlatformChannelScalar.bool(from: "true") }
    }

    private func assertInvalid(_ operation: () throws -> Void) {
        XCTAssertThrowsError(try operation()) {
            XCTAssertEqual($0 as? MlKitPluginError, .invalidArguments)
        }
    }
}
