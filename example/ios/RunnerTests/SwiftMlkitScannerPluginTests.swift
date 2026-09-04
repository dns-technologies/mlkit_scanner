import Flutter
import XCTest
@testable import mlkit_scanner

final class SwiftMlkitScannerPluginTests: XCTestCase {
    func testUnknownMethodReturnsFlutterMethodNotImplemented() {
        let plugin = makePlugin()
        var channelValue: Any?

        plugin.handle(FlutterMethodCall(methodName: "unknown", arguments: nil)) {
            channelValue = $0
        }

        XCTAssertTrue(channelValue as AnyObject === FlutterMethodNotImplemented)
    }

    func testInvalidCommandArgumentsReturnAFlutterError() {
        let plugin = makePlugin()
        var channelValue: Any?

        plugin.handle(
            FlutterMethodCall(
                methodName: PluginConstants.captureCameraMethod,
                arguments: ["viewId": NSNumber(value: true)]
            )
        ) { channelValue = $0 }

        let error = channelValue as? FlutterError
        XCTAssertEqual(error?.code, MlKitPluginError.invalidArguments.rawValue)
        XCTAssertEqual(error?.message, MlKitPluginError.invalidArguments.localizedDescription)
    }

    func testPlatformViewFactoryUsesStandardCodecAndRegistrationArguments() {
        let plugin = makePlugin()

        XCTAssertTrue(plugin.createArgsCodec() is FlutterStandardMessageCodec)
        let platformView = plugin.create(
            withFrame: CGRect(x: 10, y: 20, width: 100, height: 80),
            viewIdentifier: 42,
            arguments: ["width": 200.0, "height": 120.0]
        )

        let preview = platformView as? CameraPreview
        XCTAssertEqual(preview?.viewId, 42)
        XCTAssertEqual(preview?.view().frame, CGRect(x: 0, y: 0, width: 200, height: 120))
        preview?.dispose()
    }

    func testInvalidViewRegistrationFallsBackToTheProvidedFrame() {
        let plugin = makePlugin()

        let platformView = plugin.create(
            withFrame: CGRect(x: 10, y: 20, width: 100, height: 80),
            viewIdentifier: 42,
            arguments: ["width": "invalid"]
        )

        let preview = platformView as? CameraPreview
        XCTAssertEqual(preview?.view().frame, CGRect(x: 10, y: 20, width: 100, height: 80))
        preview?.dispose()
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
