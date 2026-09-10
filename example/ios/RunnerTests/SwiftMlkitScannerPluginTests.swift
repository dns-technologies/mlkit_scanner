import Flutter
import XCTest
@testable import mlkit_scanner

final class SwiftMlkitScannerPluginTests: XCTestCase {
    func testPauseMethodCompletesWithoutViewArgumentsAndRemainsIdempotent() {
        let plugin = makePlugin()
        var replies = 0

        for _ in 0..<2 {
            plugin.handle(FlutterMethodCall(methodName: PluginConstants.pauseCameraMethod, arguments: nil)) {
                XCTAssertNil($0)
                replies += 1
            }
        }

        XCTAssertEqual(replies, 2)
    }

    func testUnknownAndRemovedMethodsReturnFlutterMethodNotImplemented() {
        let plugin = makePlugin()
        var channelValue: Any?

        for method in ["unknown", "captureCamera", "releaseCamera"] {
            channelValue = nil
            plugin.handle(FlutterMethodCall(methodName: method, arguments: nil)) {
                channelValue = $0
            }
            XCTAssertTrue(channelValue as AnyObject === FlutterMethodNotImplemented)
        }
    }

    func testPauseAndResumeMethodsAreDispatched() {
        let plugin = makePlugin()
        var pauseReplied = false
        plugin.handle(FlutterMethodCall(methodName: "pauseCameraMethod", arguments: nil)) {
            XCTAssertNil($0)
            pauseReplied = true
        }
        XCTAssertTrue(pauseReplied)

        var resumeError: FlutterError?
        plugin.handle(FlutterMethodCall(methodName: "resumeCameraMethod", arguments: [:])) {
            resumeError = $0 as? FlutterError
        }
        XCTAssertEqual(resumeError?.code, MlKitPluginError.invalidArguments.rawValue)
    }

    func testInvalidCommandArgumentsReturnAFlutterError() {
        let plugin = makePlugin()
        var channelValue: Any?

        plugin.handle(
            FlutterMethodCall(
                methodName: PluginConstants.resumeCameraMethod,
                arguments: ["viewId": NSNumber(value: true)]
            )
        ) { channelValue = $0 }

        let error = channelValue as? FlutterError
        XCTAssertEqual(error?.code, MlKitPluginError.invalidArguments.rawValue)
        XCTAssertEqual(error?.message, MlKitPluginError.invalidArguments.localizedDescription)
    }

    func testPlatformViewFactoryRegistersAContainerWithoutAllocatingCamera() {
        let plugin = makePlugin()

        XCTAssertTrue(plugin.createArgsCodec() is FlutterStandardMessageCodec)
        let platformView = plugin.create(
            withFrame: CGRect(x: 10, y: 20, width: 100, height: 80),
            viewIdentifier: 42,
            arguments: ["width": 200.0, "height": 120.0]
        )

        let preview = platformView as? ScannerView
        XCTAssertEqual(preview?.viewId, 42)
        XCTAssertEqual(preview?.view().frame, CGRect(x: 10, y: 20, width: 100, height: 80))
        XCTAssertTrue(preview?.view().subviews.isEmpty == true)
    }

    func testInvalidViewRegistrationFallsBackToTheProvidedFrame() {
        let plugin = makePlugin()

        let platformView = plugin.create(
            withFrame: CGRect(x: 10, y: 20, width: 100, height: 80),
            viewIdentifier: 42,
            arguments: ["width": "invalid"]
        )

        let preview = platformView as? ScannerView
        XCTAssertEqual(preview?.view().frame, CGRect(x: 10, y: 20, width: 100, height: 80))
    }

    private func makePlugin() -> SwiftMlkitScannerPlugin {
        SwiftMlkitScannerPlugin(
            channel: FlutterMethodChannel(
                name: "mlkit_scanner.tests",
                binaryMessenger: TestBinaryMessenger()
            )
        )
    }
}

private final class TestBinaryMessenger: NSObject, FlutterBinaryMessenger {
    func send(onChannel channel: String, message: Data?) {}

    func send(
        onChannel channel: String,
        message: Data?,
        binaryReply callback: FlutterBinaryReply?
    ) {
        callback?(nil)
    }

    func setMessageHandlerOnChannel(
        _ channel: String,
        binaryMessageHandler handler: FlutterBinaryMessageHandler?
    ) -> FlutterBinaryMessengerConnection {
        1
    }

    func cleanUpConnection(_ connection: FlutterBinaryMessengerConnection) {}
}
