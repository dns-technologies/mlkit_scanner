import Foundation
import XCTest
@testable import mlkit_scanner

final class ArgumentUtilsTests: XCTestCase {
    func testRequireMapAcceptsStringKeysAndRejectsMalformedArguments() throws {
        XCTAssertEqual(try ArgumentUtils.requireMap(["value": 42])["value"] as? Int, 42)
        XCTAssertTrue(try ArgumentUtils.requireMap([String: Any]()).isEmpty)

        for value: Any? in [nil, NSNull(), "arguments", [42], [1: "value"]] {
            assertInvalid { _ = try ArgumentUtils.requireMap(value) }
        }
    }

    func testOptionalMapTreatsMissingAndNullValuesAsAbsent() throws {
        XCTAssertNil(try argument(nil).optionalMap("value"))
        XCTAssertNil(try argument(NSNull()).optionalMap("value"))
        XCTAssertEqual(try argument(["key": 42]).optionalMap("value")?["key"] as? Int, 42)
        assertInvalid { _ = try argument("map").optionalMap("value") }
    }

    func testRequiredNestedMapRejectsMissingNullAndNonMapValues() throws {
        XCTAssertEqual(try argument(["key": 42]).requireMap("value")["key"] as? Int, 42)
        for value: Any? in [nil, NSNull(), "map", 42] {
            assertInvalid { _ = try argument(value).requireMap("value") }
        }
    }

    func testFiniteDoublePreservesNumericValuesWithoutApplicationRanges() throws {
        for value in [0.0, -1.0, 2.5, Double.leastNonzeroMagnitude, Double.greatestFiniteMagnitude] {
            XCTAssertEqual(try argument(value).requireFiniteDouble("value"), value)
            XCTAssertEqual(try argument(value).optionalFiniteDouble("value"), value)
        }
    }

    func testFiniteDoubleRejectsNonfiniteAndNonnumericValues() {
        for value: Any? in [nil, NSNull(), true, "2.5", Double.nan, Double.infinity, -Double.infinity] {
            assertInvalid { _ = try argument(value).requireFiniteDouble("value") }
        }
        for value: Any in [true, "2.5", Double.nan, Double.infinity, -Double.infinity] {
            assertInvalid { _ = try argument(value).optionalFiniteDouble("value") }
        }
    }

    func testOptionalFiniteDoubleTreatsMissingAndNullValuesAsAbsent() throws {
        XCTAssertNil(try argument(nil).optionalFiniteDouble("value"))
        XCTAssertNil(try argument(NSNull()).optionalFiniteDouble("value"))
    }

    func testCodecNumericZeroAndOneRemainNumbers() throws {
        XCTAssertEqual(try argument(NSNumber(value: Int32(0))).requireInt64("value"), 0)
        XCTAssertEqual(try argument(NSNumber(value: Int32(1))).requireInt("value"), 1)
        XCTAssertEqual(try argument(NSNumber(value: 1.0)).requireFiniteDouble("value"), 1)
        XCTAssertEqual(try argument(NSNumber(value: Int32(150))).requireInt("value"), 150)
    }

    func testNumericArgumentsRejectCodecBooleanValues() {
        let values = argument(NSNumber(value: true))

        assertInvalid { _ = try values.requireInt("value") }
        assertInvalid { _ = try values.requireInt64("value") }
        assertInvalid { _ = try values.requireFiniteDouble("value") }
        assertInvalid { _ = try values.optionalFiniteDouble("value") }
    }

    func testBooleanAcceptsOnlyCoreFoundationBooleans() throws {
        XCTAssertTrue(try argument(NSNumber(value: true)).requireBoolean("value"))
        XCTAssertFalse(try argument(NSNumber(value: false)).requireBoolean("value"))
        for value: Any? in [nil, NSNull(), NSNumber(value: Int32(0)), NSNumber(value: Int32(1)), "true"] {
            assertInvalid { _ = try argument(value).requireBoolean("value") }
        }
    }

    func testIntegerRequiresExactNumericRepresentation() throws {
        for value in [0, 1, 150, -1, Int.min, Int.max] {
            XCTAssertEqual(try argument(value).requireInt("value"), value)
        }
        XCTAssertEqual(try argument(1.0).requireInt("value"), 1)
        XCTAssertEqual(try argument(-0.0).requireInt("value"), 0)
    }

    func testInt64PreservesFullIntegerPrecisionAndBothBoundaries() throws {
        for value: Int64 in [Int64.min, -1, 0, 1, 9_007_199_254_740_993, Int64.max] {
            XCTAssertEqual(try argument(NSNumber(value: value)).requireInt64("value"), value)
        }
    }

    func testIntegersRejectFractionsOverflowAndMalformedValues() {
        let invalid: [Any?] = [
            nil, NSNull(), true, "150", 1.5, -1.5, Double.leastNonzeroMagnitude,
            Double.nan, Double.infinity, -Double.infinity, 1e20,
            Double(Int64.max), Double(Int64.min).nextDown, NSNumber(value: UInt64.max),
        ]
        for value in invalid {
            assertInvalid { _ = try argument(value).requireInt("value") }
            assertInvalid { _ = try argument(value).requireInt64("value") }
        }
    }

    func testSizePreservesFractionalAndSubpixelDimensions() throws {
        let size = try ArgumentUtils.requireMap(["width": 320.5, "height": 240.25]).requireSize()
        XCTAssertEqual(size.width, 320.5)
        XCTAssertEqual(size.height, 240.25)
        XCTAssertEqual(try ArgumentUtils.requireMap(["width": 0.01, "height": 0.5]).requireSize().width, 0.01)
    }

    func testSizeRejectsMalformedAndNonPositiveDimensions() {
        let invalidArguments: [Any?] = [nil, "size", [String: Any](), ["width": 320], ["height": 240]]
        for arguments in invalidArguments {
            assertInvalid { _ = try ArgumentUtils.requireMap(arguments).requireSize() }
        }
        for invalid: Any in [true, "320", 0, -1, Double.nan, Double.infinity, -Double.infinity] {
            assertInvalid { _ = try ArgumentUtils.requireMap(["width": invalid, "height": 240]).requireSize() }
            assertInvalid { _ = try ArgumentUtils.requireMap(["width": 320, "height": invalid]).requireSize() }
        }
    }

    private func argument(_ value: Any?) -> [String: Any] {
        value.map { ["value": $0] } ?? [:]
    }

    private func assertInvalid(file: StaticString = #filePath, line: UInt = #line, _ operation: () throws -> Void) {
        XCTAssertThrowsError(try operation(), file: file, line: line) {
            XCTAssertEqual($0 as? MlKitPluginError, .invalidArguments, file: file, line: line)
        }
    }
}
