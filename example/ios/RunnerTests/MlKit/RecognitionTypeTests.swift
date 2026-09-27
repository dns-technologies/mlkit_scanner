import XCTest
@testable import mlkit_scanner

final class RecognitionTypeTests: XCTestCase {
    func testBarcodeRecognitionWireValue() {
        XCTAssertEqual(RecognitionType(rawValue: 0), .barcodeRecognition)
        XCTAssertNil(RecognitionType(rawValue: 1))
    }
}
