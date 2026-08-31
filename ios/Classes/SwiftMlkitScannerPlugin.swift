import Flutter
import UIKit

/// iOS plugin entry point backed by one view-scoped scanner session.
public final class SwiftMlkitScannerPlugin: NSObject, FlutterPlugin {
    private let scannerSession: ScannerSessionImpl

    /// Creates the plugin and its shared scanner session for one engine channel.
    init(channel: FlutterMethodChannel) {
        let scannerSession = ScannerSessionImpl(
            onScanResult: { viewId, barcode in
                DispatchQueue.main.async {
                    channel.invokeMethod(
                        PluginConstants.scanResultMethod,
                        arguments: [
                            PluginConstants.viewIdArgument: viewId,
                            PluginConstants.barcodeArgument: barcode.toJson(),
                        ]
                    )
                }
            },
            onTorchChanged: { viewId, value in
                DispatchQueue.main.async {
                    channel.invokeMethod(
                        PluginConstants.changeTorchStateMethod,
                        arguments: [
                            PluginConstants.viewIdArgument: viewId,
                            PluginConstants.valueArgument: value,
                        ]
                    )
                }
            }
        )
        self.scannerSession = scannerSession
        super.init()
    }

    deinit {
        scannerSession.release()
    }

    /// Registers the method channel and native camera platform-view factory.
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(
            name: PluginConstants.channelName,
            binaryMessenger: registrar.messenger()
        )
        let instance = SwiftMlkitScannerPlugin(channel: channel)
        registrar.addMethodCallDelegate(instance, channel: channel)
        registrar.register(instance, withId: PluginConstants.cameraPlatformViewName)
    }

    /// Routes a Flutter method call to the corresponding scanner command.
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case PluginConstants.captureCameraMethod:
            CaptureCameraCommand(scannerSession: scannerSession)
                .execute(call, result: result)
        case PluginConstants.releaseCameraMethod:
            ReleaseCameraCommand(scannerSession: scannerSession)
                .execute(call, result: result)
        case PluginConstants.resumeCameraMethod:
            ResumeCameraCommand(scannerSession: scannerSession)
                .execute(call, result: result)
        case PluginConstants.pauseCameraMethod:
            PauseCameraCommand(scannerSession: scannerSession)
                .execute(call, result: result)
        case PluginConstants.toggleFlashMethod:
            ToggleFlashCommand(scannerSession: scannerSession)
                .execute(call, result: result)
        case PluginConstants.startScanMethod:
            StartScanCommand(scannerSession: scannerSession)
                .execute(call, result: result)
        case PluginConstants.cancelScanMethod:
            CancelScanCommand(scannerSession: scannerSession)
                .execute(call, result: result)
        case PluginConstants.setScanDelayMethod:
            SetScanDelayCommand(scannerSession: scannerSession)
                .execute(call, result: result)
        case PluginConstants.setZoomMethod:
            SetZoomCommand(scannerSession: scannerSession)
                .execute(call, result: result)
        case PluginConstants.setCropAreaMethod:
            SetCropAreaCommand(scannerSession: scannerSession)
                .execute(call, result: result)
        case PluginConstants.getIosAvailableCamerasMethod:
            GetIosAvailableCamerasCommand(scannerSession: scannerSession)
                .execute(call, result: result)
        case PluginConstants.setIosCameraMethod:
            SetIosCameraCommand(scannerSession: scannerSession)
                .execute(call, result: result)
        default:
            result(FlutterMethodNotImplemented)
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
        let registration = (try? ScannerMethodArguments.viewRegistration(args)) ?? .empty
        return scannerSession.createView(
            frame: frame,
            viewId: viewId,
            registration: registration
        )
    }
}
