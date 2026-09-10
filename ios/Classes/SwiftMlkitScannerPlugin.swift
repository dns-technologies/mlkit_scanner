import Flutter
import UIKit

/// iOS plugin entry point backed by one shared native SDK bridge.
public final class SwiftMlkitScannerPlugin: NSObject, FlutterPlugin {
    private let scannerDevice: ScannerHardware
    private var isDisposed = false

    /// Creates the plugin and its shared SDK bridge for one engine channel.
    init(channel: FlutterMethodChannel) {
        let scannerDevice = ScannerHardware(
            onScanResult: { viewId, barcode in
                channel.invokeMethod(
                    PluginConstants.scanResultMethod,
                    arguments: [
                        PluginConstants.viewIdArgument: viewId,
                        PluginConstants.barcodeArgument: barcode.toJson(),
                    ]
                )
            },
            onTorchChanged: { viewId, value in
                channel.invokeMethod(
                    PluginConstants.changeTorchStateMethod,
                    arguments: [
                        PluginConstants.viewIdArgument: viewId,
                        PluginConstants.valueArgument: value,
                    ]
                )
            }
        )
        self.scannerDevice = scannerDevice
        super.init()
    }

    deinit {
        scannerDevice.release()
    }

    /// Registers the method channel and native camera platform-view factory.
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(
            name: PluginConstants.channelName,
            binaryMessenger: registrar.messenger()
        )
        let instance = SwiftMlkitScannerPlugin(channel: channel)
        // Engine teardown notifies published plugins; a method-call delegate alone is insufficient.
        registrar.publish(instance)
        registrar.addMethodCallDelegate(instance, channel: channel)
        registrar.register(instance, withId: PluginConstants.cameraPlatformViewName)
    }

    /// Detachment invalidates replies before resource cleanup can complete pending calls.
    public func detachFromEngine(for registrar: FlutterPluginRegistrar) {
        isDisposed = true
        scannerDevice.release()
    }

    /// Routes a Flutter method call to the corresponding scanner command.
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard !isDisposed else { return }
        let reply: FlutterResult = { [weak self] value in
            guard self?.isDisposed == false else { return }
            result(value)
        }
        switch call.method {
        case PluginConstants.resumeCameraMethod:
            CaptureCameraCommand(scannerDevice: scannerDevice)
                .execute(call, result: reply)
        case PluginConstants.pauseCameraMethod:
            scannerDevice.releaseCamera { reply(nil) }
        case PluginConstants.setZoomRatioMethod:
            SetZoomRatioCommand(scannerDevice: scannerDevice).execute(call, result: reply)
        case PluginConstants.toggleFlashMethod:
            ToggleFlashCommand(scannerDevice: scannerDevice).execute(call, result: reply)
        case PluginConstants.startScanMethod:
            StartScanCommand(scannerDevice: scannerDevice).execute(call, result: reply)
        case PluginConstants.cancelScanMethod:
            CancelScanCommand(scannerDevice: scannerDevice).execute(call, result: reply)
        case PluginConstants.setScanDelayMethod:
            SetScanDelayCommand(scannerDevice: scannerDevice).execute(call, result: reply)
        case PluginConstants.setCropAreaMethod:
            SetCropAreaCommand(scannerDevice: scannerDevice).execute(call, result: reply)
        case PluginConstants.getIosAvailableCamerasMethod:
            GetIosAvailableCamerasCommand(scannerDevice: scannerDevice)
                .execute(call, result: reply)
        default:
            reply(FlutterMethodNotImplemented)
        }
    }
}

extension SwiftMlkitScannerPlugin: FlutterPlatformViewFactory {
    /// Returns the codec used for native platform-view creation arguments.
    public func createArgsCodec() -> FlutterMessageCodec & NSObjectProtocol {
        return FlutterStandardMessageCodec.sharedInstance()
    }

    /// Creates and registers one native camera preview for `viewId`.
    public func create(
        withFrame frame: CGRect,
        viewIdentifier viewId: Int64,
        arguments args: Any?
    ) -> FlutterPlatformView {
        return scannerDevice.createView(
            frame: frame,
            viewId: viewId
        )
    }
}
