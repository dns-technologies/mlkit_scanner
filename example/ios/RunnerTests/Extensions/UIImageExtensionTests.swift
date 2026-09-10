import AVFoundation
import CoreImage
import UIKit
import XCTest
@testable import mlkit_scanner

final class UIImageExtensionTests: XCTestCase {
    func testCameraFrameInitializerAppliesPortraitScale() {
        let source = CIImage(color: .white).cropped(
            to: CGRect(x: 0, y: 0, width: 100, height: 80)
        )

        let image = UIImage(
            ciImage: source,
            scaleX: 0.5,
            scaleY: 0.25,
            orientation: .portrait,
            cropRect: nil
        )

        XCTAssertEqual(image?.size, CGSize(width: 50, height: 20))
    }

    func testCameraFrameInitializerAppliesRecognitionCrop() throws {
        let source = CIImage(color: .white).cropped(
            to: CGRect(x: 0, y: 0, width: 100, height: 80)
        )
        let crop = try CropRect(arguments: [
            "scaleWidth": 0.5,
            "scaleHeight": 0.5,
        ])

        let image = UIImage(
            ciImage: source,
            scaleX: 1,
            scaleY: 1,
            orientation: .portrait,
            cropRect: crop
        )

        XCTAssertEqual(image?.size, CGSize(width: 50, height: 48))
    }

    func testLibraryAssetLookupLoadsKnownImageAndRejectsMissingName() {
        XCTAssertNotNil(UIImage.fromLibraryAssets(name: "lock"))
        XCTAssertNil(UIImage.fromLibraryAssets(name: "missing-test-image"))
    }
}
