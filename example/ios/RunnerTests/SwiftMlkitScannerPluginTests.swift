import Flutter
import CoreMedia
import XCTest
@testable import mlkit_scanner

final class SwiftMlkitScannerPluginTests: XCTestCase {
    private var nextCapture = 0
    func testPreviewMetadataPreservesTheChannelRepresentation() {
        let preview = CameraPreviewDescription(textureId: 42, width: 720, height: 1280, state: .streaming)
        let expected: [String: Any] = ["textureId": Int64(42), "width": 720, "height": 1280,
            "rotationDegrees": 0, "mirrored": false, "state": "streaming"]
        XCTAssertEqual(SwiftMlkitScannerPlugin.previewArguments(preview) as NSDictionary, expected as NSDictionary)

        var paused = preview
        paused.state = .paused
        XCTAssertEqual(SwiftMlkitScannerPlugin.previewArguments(paused)["state"] as? String, "paused")
    }

    func testInvalidGeometryPreservesTheActiveViewport() {
        let registry = RecordingTextureRegistry()
        let camera = PluginTestCamera()
        let plugin = makePlugin(registry, camera: camera)
        let capture = capture(plugin, viewId: 1)

        XCTAssertEqual((call(plugin, "updatePreviewGeometry", ["captureId": capture, "width": 0, "height": 240]) as? FlutterError)?.code,
            MlKitPluginError.invalidArguments.rawValue)
        XCTAssertEqual(camera.geometries, [CGSize(width: 1, height: 1)])
        XCTAssertNil(call(plugin, "updatePreviewGeometry", ["captureId": capture, "width": 320.5, "height": 240.25]))
        XCTAssertEqual(camera.geometries.last, CGSize(width: 320.5, height: 240.25))
        _ = call(plugin, "disposeScanner")
    }

    func testBatchedSettingsRejectAnObsoleteLeaseBeforeValidation() {
        let plugin = makePlugin(RecordingTextureRegistry())
        let old = capture(plugin, viewId: 1)
        let current = capture(plugin, viewId: 1)
        XCTAssertEqual((call(plugin, "updateCameraSettings", ["captureId": old, "torchEnabled": 1]) as? FlutterError)?.code,
            MlKitPluginError.cameraSessionDisposed.rawValue)
        XCTAssertEqual((call(plugin, "updateCameraSettings", ["captureId": current, "torchEnabled": 1]) as? FlutterError)?.code,
            MlKitPluginError.invalidArguments.rawValue)
        _ = call(plugin, "disposeScanner")
    }

    func testCaptureReplacesOwnershipWithoutReinitializingTheCamera() {
        let camera = PluginTestCamera()
        let plugin = makePlugin(RecordingTextureRegistry(), camera: camera)
        let first = capture(plugin, viewId: 1)
        let second = capture(plugin, viewId: 2)

        XCTAssertEqual((call(plugin, "setScanDelay", ["captureId": first, "delay": 250]) as? FlutterError)?.code,
            MlKitPluginError.cameraSessionDisposed.rawValue)
        XCTAssertNil(call(plugin, "setScanDelay", ["captureId": second, "delay": 250]))
        XCTAssertEqual(camera.initializations, 1)
        XCTAssertNil(call(plugin, "disposeScanner") as? FlutterError)
    }
    func testStaleLeaseCannotPauseCurrentOwner() {
        let plugin = makePlugin(RecordingTextureRegistry())
        let old = capture(plugin, viewId: 1)
        let current = capture(plugin, viewId: 1)
        XCTAssertEqual((call(plugin, "pauseCameraMethod", ["captureId": old]) as? FlutterError)?.code,
            MlKitPluginError.cameraSessionDisposed.rawValue)
        _ = call(plugin, "release", ["captureId": old])
        XCTAssertNil(call(plugin, "pauseCameraMethod", ["captureId": current]))
        _ = call(plugin, "disposeScanner")
    }
    func testPreviewSubscriptionsReturnSnapshotAndDistinctSubscriptionIds() {
        let plugin = makePlugin(RecordingTextureRegistry())
        let a = call(plugin, "subscribePreview") as! [String: Any]
        let b = call(plugin, "subscribePreview") as! [String: Any]
        XCTAssertNotEqual(a["subscriptionId"] as? String, b["subscriptionId"] as? String)
        XCTAssertTrue(a["description"] is NSNull)
        _ = call(plugin, "unsubscribePreview", ["subscriptionId": a["subscriptionId"]!])
        _ = call(plugin, "disposeScanner")
    }
    func testUnsubscribingPreviewStopsDeliveryWithoutRemovingOtherSubscribers() {
        let messenger = TestBinaryMessenger()
        let plugin = makePlugin(RecordingTextureRegistry(), messenger: messenger)
        let removed = call(plugin, "subscribePreview") as! [String: Any]
        let active = call(plugin, "subscribePreview") as! [String: Any]
        let capture = capture(plugin, viewId: 1)
        _ = call(plugin, "release", ["captureId": capture])

        _ = call(plugin, "unsubscribePreview", ["subscriptionId": removed["subscriptionId"]!])
        _ = call(plugin, "disposeScanner")

        XCTAssertFalse(messenger.calls.isEmpty)
        for event in messenger.calls {
            XCTAssertEqual(event.method, "onPreviewState")
            let arguments = event.arguments as? [String: Any]
            XCTAssertEqual(arguments?["subscriptionId"] as? String, active["subscriptionId"] as? String)
            XCTAssertTrue(arguments?["description"] is NSNull)
        }
    }

    func testPreviewUnsubscribeDuringDeliverySuppressesRemainingQueuedSubscribers() {
        let messenger = TestBinaryMessenger()
        let plugin = makePlugin(RecordingTextureRegistry(), messenger: messenger)
        let subscriptions = (0..<2).map { _ in call(plugin, "subscribePreview") as! [String: Any] }
        messenger.onSend = {
            for subscription in subscriptions {
                _ = self.call(plugin, "unsubscribePreview", ["subscriptionId": subscription["subscriptionId"]!])
            }
        }

        _ = call(plugin, "disposeScanner")

        XCTAssertEqual(messenger.calls.count, 1)
        messenger.onSend = nil
    }

    func testScanCanBeCancelledAndRestartedWithoutReacquiringCamera() {
        let registry = RecordingTextureRegistry()
        let plugin = makePlugin(registry)
        let capture = capture(plugin, viewId: 1)

        XCTAssertNil(call(plugin, "startScan", ["captureId": capture, "subscriptionId": "first", "delay": 0]))
        XCTAssertNil(call(plugin, "cancelScan", ["captureId": capture]))
        XCTAssertNil(call(plugin, "startScan", ["captureId": capture, "subscriptionId": "second", "delay": 0]))
        XCTAssertTrue(registry.registered.isEmpty)
        _ = call(plugin, "disposeScanner")
    }

    func testScanLeaseRejectsMissingOrEmptyEndpointAndInvalidDelay() {
        let plugin = makePlugin(RecordingTextureRegistry())
        let capture = capture(plugin, viewId: 1)
        let arguments: [[String: Any]] = [
            ["captureId": capture, "delay": 0],
            ["captureId": capture, "subscriptionId": "", "delay": 0],
            ["captureId": capture, "subscriptionId": "scan", "delay": -1],
        ]
        for values in arguments {
            XCTAssertEqual((call(plugin, "startScan", values) as? FlutterError)?.code, MlKitPluginError.invalidArguments.rawValue)
        }
        _ = call(plugin, "disposeScanner")
    }

    func testCancellingChildLeaseDuringCapturePreventsLateScanStartup() {
        let camera = PluginTestCamera()
        camera.holdResume = true
        let analyzer = PluginTestAnalyzer()
        let plugin = makePlugin(RecordingTextureRegistry(), camera: camera, analyzer: analyzer)
        var replies = 0
        plugin.handle(FlutterMethodCall(methodName: "capture", arguments: [
            "viewId": 1, "captureId": "capture", "subscriptionId": "old-scan", "delay": 100,
            "configuration": ["zoomRatio": 1.0, "torchEnabled": false],
            "geometry": ["width": 100.0, "height": 100.0],
        ])) { XCTAssertNil($0); replies += 1 }
        XCTAssertEqual(replies, 0)

        XCTAssertNil(call(plugin, "cancelScan", ["captureId": "capture"]))
        camera.pendingResume?(nil)

        XCTAssertEqual(replies, 1)
        XCTAssertNil(analyzer.subscription)
        _ = call(plugin, "disposeScanner")
    }

    func testConsumerCommandsRejectMalformedViewIds() {
        let registry = RecordingTextureRegistry()
        let plugin = makePlugin(registry)
        let invalidArguments: [[String: Any]] = [
            [:], ["viewId": -1], ["viewId": 1.5], ["viewId": 1e20],
            ["viewId": NSNumber(value: true)],
        ]

        for method in ["capture"] {
            for arguments in invalidArguments {
                XCTAssertEqual((call(plugin, method, arguments) as? FlutterError)?.code,
                    MlKitPluginError.invalidArguments.rawValue)
            }
        }

        XCTAssertTrue(registry.registered.isEmpty)
        _ = call(plugin, "disposeScanner")
    }

    private func call(_ plugin: SwiftMlkitScannerPlugin, _ method: String, _ arguments: [String: Any] = [:]) -> Any? {
        var value: Any?; var replies = 0
        plugin.handle(FlutterMethodCall(methodName: method, arguments: arguments)) { value = $0; replies += 1 }
        XCTAssertEqual(replies, 1)
        return value
    }
    private func capture(_ plugin: SwiftMlkitScannerPlugin, viewId: Int64) -> String {
        nextCapture += 1
        let id = "capture-\(nextCapture)"
        XCTAssertNil(call(plugin, "capture", ["viewId": viewId, "captureId": id,
            "configuration": ["zoomRatio": 1.0, "torchEnabled": false],
            "geometry": ["width": 1.0, "height": 1.0], "delay": 0]))
        return id
    }

    private func makePlugin(_ registry: FlutterTextureRegistry, messenger: TestBinaryMessenger = TestBinaryMessenger(),
                            camera: PluginTestCamera = PluginTestCamera(), analyzer: PluginTestAnalyzer = PluginTestAnalyzer()) -> SwiftMlkitScannerPlugin {
        let scanner = mlkit_scanner.Scanner(onScanResult: { _, _ in }, onTorchChanged: { _, _ in },
            onPreviewChanged: { _ in }, cameraFactory: { camera }, analyzerFactory: { analyzer },
            requestPermission: { $0(true) })
        return SwiftMlkitScannerPlugin(channel: FlutterMethodChannel(name: "scanner.tests", binaryMessenger: messenger), textures: registry, scanner: scanner)
    }
}
private final class TestBinaryMessenger: NSObject, FlutterBinaryMessenger {
    var calls: [FlutterMethodCall] = []
    var onSend: (() -> Void)?

    func send(onChannel channel: String, message: Data?) {
        if let message = message {
            calls.append(FlutterStandardMethodCodec.sharedInstance().decodeMethodCall(message))
            onSend?()
        }
    }

    func send(onChannel channel: String, message: Data?, binaryReply callback: FlutterBinaryReply?) {
        send(onChannel: channel, message: message)
        callback?(nil)
    }
    func setMessageHandlerOnChannel(_ channel: String, binaryMessageHandler handler: FlutterBinaryMessageHandler?) -> FlutterBinaryMessengerConnection { 1 }
    func cleanUpConnection(_ connection: FlutterBinaryMessengerConnection) {}
}

private final class PluginTestCamera: CameraPreviewing {
    var initializations = 0
    var geometries: [CGSize] = []
    var holdResume = false
    var pendingResume: ((Error?) -> Void)?
    var isInitialized = false
    var isTorchActive = false
    var recognitionHandler: RecognitionHandler?
    weak var cameraPreviewDelegate: CameraPreviewDelegate?

    func initCamera(completion: @escaping (Error?) -> Void) { initializations += 1; isInitialized = true; completion(nil) }
    func prepare(_ settings: ScannerConfiguration, geometry: CGSize, completion: @escaping (Error?) -> Void) { geometries.append(geometry); completion(nil) }
    func cancelPendingStart(completion: @escaping () -> Void) { completion() }
    func setCamera(_ cameraData: CameraData) throws {}
    func setFlash(_ enabled: Bool) throws { isTorchActive = enabled }
    func resetFocus() {}
    func focus(locked: Bool) throws {}
    func resumeCamera(completion: @escaping (Error?) -> Void) { if holdResume { pendingResume = completion } else { completion(nil) } }
    func pauseCamera(completion: @escaping () -> Void) { completion() }
    func setZoomRatio(_ value: Double) throws {}
    func setCropArea(_ cropRect: CropRect) {}
    func updateGeometry(_ size: CGSize) { geometries.append(size) }
    func dispose(completion: @escaping () -> Void) { completion() }
}

private final class PluginTestAnalyzer: BarcodeAnalyzing {
    var subscription: ScanResultSubscription?

    func setDelay(delay: Int) {}
    func updateCropRect(cropRect: CropRect) {}
    func subscribe(_ onResult: @escaping (ScannerBarcode) -> Void) -> ScanResultSubscription {
        unsubscribe()
        let result = ScanResultSubscription(onResult)
        subscription = result
        return result
    }
    func input(for listener: ScanResultSubscription) -> RecognitionHandler { PluginRecognitionHandler() }
    func unsubscribe() { subscription?.cancel(); subscription = nil }
}

private final class PluginRecognitionHandler: RecognitionHandler {
    func processVideoOutput(sampleBuffer: CMSampleBuffer, viewport: CGSize) {}
}
