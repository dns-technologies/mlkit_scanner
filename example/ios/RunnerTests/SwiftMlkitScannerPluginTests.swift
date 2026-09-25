import Flutter
import XCTest
@testable import mlkit_scanner

final class SwiftMlkitScannerPluginTests: XCTestCase {
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
    func testPreviewSubscriptionsReturnSnapshotAndDistinctEndpointIds() {
        let plugin = makePlugin(RecordingTextureRegistry())
        let a = call(plugin, "subscribePreview") as! [String: Any]
        let b = call(plugin, "subscribePreview") as! [String: Any]
        XCTAssertNotEqual(a["subscriptionId"] as? String, b["subscriptionId"] as? String)
        XCTAssertTrue(a["description"] is NSNull)
        _ = call(plugin, "unsubscribePreview", ["subscriptionId": a["subscriptionId"]!])
        _ = call(plugin, "disposeScanner")
    }
    private func call(_ plugin: SwiftMlkitScannerPlugin, _ method: String, _ arguments: [String: Any] = [:]) -> Any? {
        var value: Any?; var replies = 0
        plugin.handle(FlutterMethodCall(methodName: method, arguments: arguments)) { value = $0; replies += 1 }
        XCTAssertEqual(replies, 1)
        return value
    }
    private func makePlugin(_ registry: FlutterTextureRegistry) -> SwiftMlkitScannerPlugin {
        SwiftMlkitScannerPlugin(channel: FlutterMethodChannel(name: "scanner.tests", binaryMessenger: TestBinaryMessenger()), textures: registry)
    }
}
private final class TestBinaryMessenger: NSObject, FlutterBinaryMessenger {
    func send(onChannel channel: String, message: Data?) {}
    func send(onChannel channel: String, message: Data?, binaryReply callback: FlutterBinaryReply?) { callback?(nil) }
    func setMessageHandlerOnChannel(_ channel: String, binaryMessageHandler handler: FlutterBinaryMessageHandler?) -> FlutterBinaryMessengerConnection { 1 }
    func cleanUpConnection(_ connection: FlutterBinaryMessengerConnection) {}
}
