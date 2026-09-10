import CoreImage
import MLKitBarcodeScanning
import MLKitVision
import UIKit
import XCTest
@testable import mlkit_scanner

final class BarcodeExtensionTests: XCTestCase {
    func testToJsonMapsRecognizedBarcodeValuesForThePlatformChannel() throws {
        let barcode = try recognizeQRCode(containing: "mlkit-scanner-test")

        let json = barcode.toJson()

        XCTAssertEqual(json["raw_value"] as? String, "mlkit-scanner-test")
        XCTAssertEqual(json["display_value"] as? String, "mlkit-scanner-test")
        XCTAssertEqual(json["format"] as? Int, BarcodeFormat.qrCode.rawValue)
        XCTAssertEqual(json["value_type"] as? Int, BarcodeValueType.text.rawValue)
    }

    private func recognizeQRCode(containing value: String) throws -> Barcode {
        let image = try qrCodeImage(containing: value)
        let scanner = BarcodeScanner.barcodeScanner(
            options: BarcodeScannerOptions(formats: .qrCode)
        )
        let expectation = expectation(description: "QR code recognition")
        var recognizedBarcode: Barcode?
        var recognitionError: Error?

        scanner.process(VisionImage(image: image)) { barcodes, error in
            recognizedBarcode = barcodes?.first
            recognitionError = error
            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 5)
        if let recognitionError {
            throw recognitionError
        }
        return try XCTUnwrap(recognizedBarcode)
    }

    private func qrCodeImage(containing value: String) throws -> UIImage {
        let filter = try XCTUnwrap(CIFilter(name: "CIQRCodeGenerator"))
        filter.setValue(Data(value.utf8), forKey: "inputMessage")
        filter.setValue("H", forKey: "inputCorrectionLevel")
        let output = try XCTUnwrap(filter.outputImage)
            .transformed(by: CGAffineTransform(scaleX: 12, y: 12))
        let cgImage = try XCTUnwrap(
            CIContext().createCGImage(output, from: output.extent)
        )
        return UIImage(cgImage: cgImage)
    }
}
