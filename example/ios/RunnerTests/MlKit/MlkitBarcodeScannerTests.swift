import AVFoundation
import CoreImage
import CoreMedia
import CoreVideo
import UIKit
import XCTest
@testable import mlkit_scanner

final class MlkitBarcodeScannerTests: XCTestCase {
    func testProcessVideoOutputRecognizesBarcodeForItsSubscription() throws {
        let scanner = MlkitBarcodeScanner(delay: 0, cropRect: nil)
        var barcode: ScannerBarcode?
        scanner.setDelay(delay: 0)
        scanner.updateCropRect(cropRect: try CropRect(arguments: [:]))
        let sampleBuffer = try makeSampleBuffer(
            from: qrCodeImage(containing: "mlkit-scanner-test")
        )
        let expectation = expectation(description: "scanner result")
        _ = scanner.subscribe { barcode = $0; expectation.fulfill() }

        scanner.processVideoOutput(
            sampleBuffer: sampleBuffer,
            viewport: CGSize(width: 400, height: 400)
        )

        wait(for: [expectation], timeout: 5)
        XCTAssertEqual(barcode?.rawValue, "mlkit-scanner-test")
        scanner.unsubscribe()
    }

    func testReplacingAndRemovingSubscriptionSuppressesObsoleteDeliveries() {
        let scanner = MlkitBarcodeScanner(delay: 0, cropRect: nil)
        var previousResults = 0
        var currentResults = 0
        let previous = scanner.subscribe { _ in previousResults += 1 }
        let barcode = ScannerBarcode(rawValue: "test", displayValue: nil, format: 256, valueType: 7)
        previous.deliver(barcode)

        let current = scanner.subscribe { _ in currentResults += 1 }
        previous.deliver(barcode)
        current.deliver(barcode)

        XCTAssertEqual(previousResults, 1)
        XCTAssertEqual(currentResults, 1)
        scanner.unsubscribe()
        current.deliver(barcode)

        XCTAssertEqual(currentResults, 1)
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

    private func makeSampleBuffer(from image: UIImage) throws -> CMSampleBuffer {
        let cgImage = try XCTUnwrap(image.cgImage)
        var pixelBuffer: CVPixelBuffer?
        let attributes: [CFString: Any] = [
            kCVPixelBufferCGImageCompatibilityKey: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey: true,
        ]
        let pixelBufferStatus = CVPixelBufferCreate(
            kCFAllocatorDefault,
            cgImage.width,
            cgImage.height,
            kCVPixelFormatType_32BGRA,
            attributes as CFDictionary,
            &pixelBuffer
        )
        XCTAssertEqual(pixelBufferStatus, kCVReturnSuccess)
        let unwrappedPixelBuffer = try XCTUnwrap(pixelBuffer)

        CVPixelBufferLockBaseAddress(unwrappedPixelBuffer, [])
        defer { CVPixelBufferUnlockBaseAddress(unwrappedPixelBuffer, []) }
        let context = try XCTUnwrap(CGContext(
            data: CVPixelBufferGetBaseAddress(unwrappedPixelBuffer),
            width: cgImage.width,
            height: cgImage.height,
            bitsPerComponent: 8,
            bytesPerRow: CVPixelBufferGetBytesPerRow(unwrappedPixelBuffer),
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue
                | CGBitmapInfo.byteOrder32Little.rawValue
        ))
        context.draw(
            cgImage,
            in: CGRect(x: 0, y: 0, width: cgImage.width, height: cgImage.height)
        )

        var formatDescription: CMVideoFormatDescription?
        let formatStatus = CMVideoFormatDescriptionCreateForImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: unwrappedPixelBuffer,
            formatDescriptionOut: &formatDescription
        )
        XCTAssertEqual(formatStatus, noErr)
        var timing = CMSampleTimingInfo(
            duration: .invalid,
            presentationTimeStamp: .zero,
            decodeTimeStamp: .invalid
        )
        var sampleBuffer: CMSampleBuffer?
        let sampleStatus = CMSampleBufferCreateReadyWithImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: unwrappedPixelBuffer,
            formatDescription: try XCTUnwrap(formatDescription),
            sampleTiming: &timing,
            sampleBufferOut: &sampleBuffer
        )
        XCTAssertEqual(sampleStatus, noErr)
        return try XCTUnwrap(sampleBuffer)
    }
}
