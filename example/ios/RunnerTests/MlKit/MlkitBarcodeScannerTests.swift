import AVFoundation
import CoreImage
import CoreMedia
import CoreVideo
import MLKitBarcodeScanning
import UIKit
import XCTest
@testable import mlkit_scanner

final class MlkitBarcodeScannerTests: XCTestCase {
    func testProcessVideoOutputRecognizesBarcodeAndPreservesViewIdentity() throws {
        let scanner = MlkitBarcodeScanner(delay: 0, cropRect: nil, viewId: 42)
        let delegate = RecognitionDelegateSpy()
        scanner.delegate = delegate
        scanner.setDelay(delay: 0)
        scanner.updateCropRect(cropRect: try CropRect(arguments: [:]))
        let sampleBuffer = try makeSampleBuffer(
            from: qrCodeImage(containing: "mlkit-scanner-test")
        )
        let expectation = expectation(description: "scanner result")
        delegate.onResult = { expectation.fulfill() }

        scanner.processVideoOutput(
            sampleBuffer: sampleBuffer,
            scaleX: 1,
            scaleY: 1,
            orientation: .portrait
        )

        wait(for: [expectation], timeout: 5)
        XCTAssertNil(delegate.error)
        XCTAssertEqual(delegate.barcode?.rawValue, "mlkit-scanner-test")
        XCTAssertEqual(delegate.viewId, 42)
        XCTAssertEqual(scanner.type, .barcodeRecognition)
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

private final class RecognitionDelegateSpy: RecognitionResultDelegate {
    var barcode: Barcode?
    var viewId: Int64?
    var error: Error?
    var onResult: (() -> Void)?

    func onRecognition(result: Barcode, viewId: Int64) {
        barcode = result
        self.viewId = viewId
        onResult?()
    }

    func onError(error: Error) {
        self.error = error
        onResult?()
    }
}
