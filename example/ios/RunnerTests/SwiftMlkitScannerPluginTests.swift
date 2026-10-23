import Flutter
import XCTest
@testable import mlkit_scanner

final class SwiftMlkitScannerPluginTests: XCTestCase {
    func testPreviewMetadataPreservesTheChannelRepresentation() {
        let preview = CameraPreviewDescription(textureId: 42, width: 720, height: 1280, state: .streaming)
        let expected: [String: Any] = ["textureId": Int64(42), "width": 720, "height": 1280,
            "rotationDegrees": 0, "mirrored": false, "state": "streaming"]
        XCTAssertEqual(SwiftMlkitScannerPlugin.previewArguments(preview) as NSDictionary, expected as NSDictionary)

        var paused = preview
        paused.state = .paused
        XCTAssertEqual(SwiftMlkitScannerPlugin.previewArguments(paused)["state"] as? String, "paused")
    }

    func testGeometryRejectsInvalidChannelValuesWithoutAllocatingCamera() {
        let registry = RecordingTextureRegistry()
        let plugin = makePlugin(registry)
        _ = call(plugin, "registerScanner", ["viewId": 1])
        let capture = call(plugin, "openCapture", ["viewId": 1]) as! String

        XCTAssertEqual((call(plugin, "updatePreviewGeometry", ["captureId": capture, "width": 0, "height": 240]) as? FlutterError)?.code,
            MlKitPluginError.invalidArguments.rawValue)
        XCTAssertNil(call(plugin, "updatePreviewGeometry", ["captureId": capture, "width": 320.5, "height": 240.25]))
        XCTAssertTrue(registry.registered.isEmpty)
        _ = call(plugin, "disposeScanner")
    }

    func testBatchedSettingsRejectAnObsoleteLeaseBeforeValidation() {
        let plugin = makePlugin(RecordingTextureRegistry())
        _ = call(plugin, "registerScanner", ["viewId": 1])
        let old = call(plugin, "openCapture", ["viewId": 1]) as! String
        let current = call(plugin, "openCapture", ["viewId": 1]) as! String
        XCTAssertEqual((call(plugin, "updateCameraSettings", ["captureId": old, "torchEnabled": 1]) as? FlutterError)?.code,
            MlKitPluginError.cameraSessionDisposed.rawValue)
        XCTAssertEqual((call(plugin, "updateCameraSettings", ["captureId": current, "torchEnabled": 1]) as? FlutterError)?.code,
            MlKitPluginError.invalidArguments.rawValue)
        _ = call(plugin, "disposeScanner")
    }

    func testRegistrationDoesNotAllocateTextureAndCaptureHasRealIdentity() {
        let registry = RecordingTextureRegistry()
        let plugin = makePlugin(registry)
        XCTAssertNil(call(plugin, "registerScanner", ["viewId": 1]) as? FlutterError)
        let first = call(plugin, "openCapture", ["viewId": 1]) as? String
        let second = call(plugin, "openCapture", ["viewId": 1]) as? String
        XCTAssertNotNil(first); XCTAssertNotEqual(first, second)
        XCTAssertTrue(registry.registered.isEmpty)
        XCTAssertNil(call(plugin, "disposeScanner") as? FlutterError)
    }
    func testStaleLeaseCannotPauseCurrentOwner() {
        let plugin = makePlugin(RecordingTextureRegistry())
        _ = call(plugin, "registerScanner", ["viewId": 1])
        let old = call(plugin, "openCapture", ["viewId": 1]) as! String
        let current = call(plugin, "openCapture", ["viewId": 1]) as! String
        XCTAssertEqual((call(plugin, "pauseCameraMethod", ["captureId": old]) as? FlutterError)?.code,
            MlKitPluginError.cameraSessionDisposed.rawValue)
        _ = call(plugin, "closeCapture", ["captureId": old])
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
        _ = call(plugin, "registerScanner", ["viewId": 1])
        let capture = call(plugin, "openCapture", ["viewId": 1]) as! String
        _ = call(plugin, "closeCapture", ["captureId": capture])

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

    func testStartingAnObsoleteScanSubscriptionIsRejected() {
        let plugin = makePlugin(RecordingTextureRegistry())
        _ = call(plugin, "registerScanner", ["viewId": 1])
        let capture = call(plugin, "openCapture", ["viewId": 1]) as! String
        let old = call(plugin, "subscribeScan", ["captureId": capture]) as! String
        let current = call(plugin, "subscribeScan", ["captureId": capture]) as! String

        let staleResult = call(plugin, "startScan", ["captureId": capture, "subscriptionId": old, "type": 0, "delay": 0])
        let currentResult = call(plugin, "startScan", ["captureId": capture, "subscriptionId": current, "type": 0, "delay": 0])

        XCTAssertNotEqual(old, current)
        XCTAssertEqual((staleResult as? FlutterError)?.code, MlKitPluginError.invalidArguments.rawValue)
        XCTAssertNil(currentResult)
        _ = call(plugin, "disposeScanner")
    }

    func testCancelledScanSubscriptionCannotRestartWhileItsCaptureRemainsOpen() {
        let plugin = makePlugin(RecordingTextureRegistry())
        _ = call(plugin, "registerScanner", ["viewId": 1])
        let capture = call(plugin, "openCapture", ["viewId": 1]) as! String
        let subscription = call(plugin, "subscribeScan", ["captureId": capture]) as! String
        _ = call(plugin, "startScan", ["captureId": capture, "subscriptionId": subscription, "type": 0, "delay": 0])

        let cancellation = call(plugin, "cancelScan", ["captureId": capture])
        let restart = call(plugin, "startScan", ["captureId": capture, "subscriptionId": subscription, "type": 0, "delay": 0])

        XCTAssertNil(cancellation)
        XCTAssertEqual((restart as? FlutterError)?.code, MlKitPluginError.invalidArguments.rawValue)
        _ = call(plugin, "disposeScanner")
    }

    func testConsumerCommandsRejectMalformedViewIds() {
        let registry = RecordingTextureRegistry()
        let plugin = makePlugin(registry)
        let invalidArguments: [[String: Any]] = [
            [:], ["viewId": -1], ["viewId": 1.5], ["viewId": 1e20],
            ["viewId": NSNumber(value: true)],
        ]

        for method in ["registerScanner", "unregisterScanner", "openCapture"] {
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
    private func makePlugin(_ registry: FlutterTextureRegistry, messenger: TestBinaryMessenger = TestBinaryMessenger()) -> SwiftMlkitScannerPlugin {
        SwiftMlkitScannerPlugin(channel: FlutterMethodChannel(name: "scanner.tests", binaryMessenger: messenger), textures: registry)
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
