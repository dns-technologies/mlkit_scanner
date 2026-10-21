import Flutter
import CoreGraphics
import CoreMedia
import Foundation
import XCTest
@testable import mlkit_scanner

final class BaseScannerCommandTests: XCTestCase {
    private var fixture: ScannerCommandFixture!

    override func setUpWithError() throws {
        fixture = try ScannerCommandFixture(activate: false)
    }

    override func tearDown() {
        fixture?.scanner.release()
        fixture = nil
        super.tearDown()
    }

    func testSuccessCompletesFlutterResultWithNil() {
        let command = BaseScannerCommand(scanner: fixture.scanner)
        var invocationCount = 0
        var channelValue: Any? = true

        command.success {
            invocationCount += 1
            channelValue = $0
        }

        XCTAssertEqual(invocationCount, 1)
        XCTAssertNil(channelValue)
    }

    func testCompleteMapsOptionalErrorToSuccessOrFailure() {
        let command = BaseScannerCommand(scanner: fixture.scanner)
        var successValue: Any? = true
        var failureValue: Any?

        command.complete({ successValue = $0 }, error: nil)
        command.complete(
            { failureValue = $0 },
            error: MlKitPluginError.cameraSessionDisposed
        )

        XCTAssertNil(successValue)
        let flutterError = failureValue as? FlutterError
        XCTAssertEqual(flutterError?.code, "8")
        XCTAssertEqual(flutterError?.message, "Camera session has been disposed")
    }

    func testCameraControlFailureUsesStableCodeAndStructuredDetails() {
        let command = BaseScannerCommand(scanner: fixture.scanner)
        let error = CameraError(
            message: "Failed to set camera zoom ratio to 2.0",
            viewId: 42,
            underlyingError: BaseScannerCommandTestError.rejected
        )
        var channelValue: Any?

        command.reportError({ channelValue = $0 }, error: error)

        let flutterError = channelValue as? FlutterError
        XCTAssertEqual(flutterError?.code, "9")
        XCTAssertEqual(flutterError?.message, "Failed to set camera zoom ratio to 2.0")
        let details = flutterError?.details as? [String: Any]
        XCTAssertNil(details?["operation"])
        XCTAssertEqual(details?["viewId"] as? Int64, 42)
        XCTAssertNotNil(details?["cause"])
    }

    func testUnknownFailureKeepsItsLocalizedDescription() {
        let command = BaseScannerCommand(scanner: fixture.scanner)
        var channelValue: Any?

        command.reportError(
            { channelValue = $0 },
            error: BaseScannerCommandTestError.rejected
        )

        let flutterError = channelValue as? FlutterError
        XCTAssertEqual(flutterError?.code, MlKitPluginError.unknownError.rawValue)
        XCTAssertEqual(
            flutterError?.message,
            BaseScannerCommandTestError.rejected.localizedDescription
        )
    }
}

private enum BaseScannerCommandTestError: Error {
    /// An operation was rejected.
    case rejected
}

/// Exercises concrete scanner commands with observable camera and analyzer boundaries.
final class ScannerCommandFixture {
    final class Camera: CameraPreviewing {
        var isInitialized = false
        var isTorchActive = false
        var recognitionHandler: RecognitionHandler?
        weak var cameraPreviewDelegate: CameraPreviewDelegate?
        var captureViewId: Int64?
        var torchEnabled: Bool?
        var zoomRatio: Double?
        var cropRect: CropRect?
        var initializationError: Error?
        var error: Error?

        func initCamera(completion: @escaping (Error?) -> Void) {
            isInitialized = initializationError == nil
            completion(initializationError)
        }

        func prepare(_ settings: ScannerConfiguration, geometry: CGSize, completion: @escaping (Error?) -> Void) {
            isTorchActive = settings.torchEnabled
            completion(nil)
        }

        func cancelPendingStart(completion: @escaping () -> Void) { completion() }
        func setCamera(_ cameraData: CameraData) throws {}

        func setFlash(_ enabled: Bool) throws {
            if let error = error { throw error }
            torchEnabled = enabled
            isTorchActive = enabled
        }

        func resetFocus() {}
        func focus(locked: Bool) throws {}
        func resumeCamera(completion: @escaping (Error?) -> Void) { completion(nil) }
        func pauseCamera(completion: @escaping () -> Void) { completion() }

        func setZoomRatio(_ value: Double) throws {
            if let error = error { throw error }
            zoomRatio = value
        }

        func setCropArea(_ cropRect: CropRect) { self.cropRect = cropRect }
        func updateGeometry(_ size: CGSize) {}

        func dispose(completion: @escaping () -> Void) {
            isInitialized = false
            completion()
        }
    }

    final class Analyzer: BarcodeAnalyzing, RecognitionHandler {
        let type = RecognitionType.barcodeRecognition
        var scanDelay: Int?
        var subscription: ScanResultSubscription?

        func setDelay(delay: Int) { scanDelay = delay }
        func updateCropRect(cropRect: CropRect) {}

        func subscribe(_ onResult: @escaping (ScannerBarcode) -> Void) -> ScanResultSubscription {
            unsubscribe()
            let listener = ScanResultSubscription(onResult)
            subscription = listener
            return listener
        }

        func input(for listener: ScanResultSubscription) -> RecognitionHandler { self }

        func unsubscribe() {
            subscription?.cancel()
            subscription = nil
        }

        func processVideoOutput(sampleBuffer: CMSampleBuffer, viewport: CGSize) {}
    }

    let camera: Camera
    let analyzer: Analyzer
    let scanner: mlkit_scanner.Scanner

    init(activate: Bool = true) throws {
        let camera = Camera()
        let analyzer = Analyzer()
        self.camera = camera
        self.analyzer = analyzer
        scanner = mlkit_scanner.Scanner(
            onScanResult: { _, _ in },
            onTorchChanged: { viewId, _ in camera.captureViewId = viewId },
            notificationCenter: NotificationCenter(),
            cameraFactory: { camera },
            analyzerFactory: { analyzer },
            requestPermission: { $0(true) }
        )
        scanner.register(viewId: 42)
        if activate {
            var completed = false
            var failure: Error?
            let configuration = try ScannerConfiguration(arguments: ["zoomRatio": 1.0, "torchEnabled": false])
            scanner.captureCamera(viewId: 42, configuration: configuration) {
                failure = $0
                completed = true
            }
            if let failure = failure { throw failure }
            XCTAssertTrue(completed, "Fixture capture must complete synchronously")
        }
    }

    deinit {
        scanner.release()
    }
}
