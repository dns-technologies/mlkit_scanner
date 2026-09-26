import Flutter
import UIKit

/// One plugin per engine; native replies and subscriptions belong to concrete capture leases.
public final class SwiftMlkitScannerPlugin: NSObject, FlutterPlugin {
    /// Method channel for this Flutter engine.
    private let channel: FlutterMethodChannel
    /// Texture registry owned by this Flutter engine.
    private let textures: FlutterTextureRegistry
    /// Active capture lease, accessed on main.
    private var selected: CaptureLease?
    /// Preview subscriptions keyed by their opaque identifiers on main.
    private var subscriptions: [String: PreviewSubscription] = [:]
    /// Latest shared preview description, cached on main.
    private var preview: [String: Any]?
    /// Whether the plugin has detached from its Flutter engine.
    private var isDisposed = false
    /// Shared native scanner with main-thread lease-aware event routing.
    private lazy var scannerDevice = ScannerHardware(
        onScanResult: { [weak self] viewId, barcode in
            guard let self = self, let lease = self.selected, !lease.closed, lease.viewId == viewId,
                  let endpoint = lease.scan, endpoint.enabled else { return }
            self.channel.invokeMethod(PluginConstants.scanResultMethod, arguments: [
                "viewId": viewId, "captureId": lease.id, "subscriptionId": endpoint.id, "barcode": barcode.toJson()])
        }, onTorchChanged: { [weak self] viewId, enabled in
            guard let self = self, let lease = self.selected, !lease.closed, lease.viewId == viewId else { return }
            self.channel.invokeMethod(PluginConstants.changeTorchStateMethod,
                arguments: ["viewId": viewId, "captureId": lease.id, "value": enabled])
        }, onPreviewChanged: { [weak self] description in
            self?.publishPreview(description)
        }, cameraFactory: { [unowned self] in
            let output: CameraPreviewOutput = CameraTextureOutput(registry: self.textures)
            return CameraPreview(output: output)
        })

    /// Binds the engine channel and texture registry.
    init(channel: FlutterMethodChannel, textures: FlutterTextureRegistry) {
        self.channel = channel
        self.textures = textures
        super.init()
    }

    /// Registers one scanner plugin instance with the Flutter engine.
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: PluginConstants.channelName, binaryMessenger: registrar.messenger())
        let instance = SwiftMlkitScannerPlugin(channel: channel, textures: registrar.textures())
        registrar.publish(instance)
        registrar.addMethodCallDelegate(instance, channel: channel)
    }

    /// Revokes active responses and releases resources when the engine detaches.
    public func detachFromEngine(for registrar: FlutterPluginRegistrar) {
        closeSelected()
        isDisposed = true
        subscriptions.removeAll()
        scannerDevice.release()
    }

    /// Routes method calls through the current capture lease and its pending replies.
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard !isDisposed else { return }
        let reply = result
        let command = BaseScannerCommand(scannerDevice: scannerDevice)
        do {
            let values = call.arguments as? [String: Any] ?? [:]
            switch call.method {
            case "registerScanner":
                scannerDevice.register(viewId: try ScannerMethodArguments.viewId(values))
                reply(preview)
            case "unregisterScanner":
                let viewId = try ScannerMethodArguments.viewId(values)
                if selected?.viewId == viewId { closeSelected() }
                scannerDevice.unregister(viewId: viewId)
                reply(nil)
            case "subscribePreview":
                let subscription = PreviewSubscription()
                subscriptions[subscription.id] = subscription
                reply(["subscriptionId": subscription.id, "description": preview as Any? ?? NSNull()])
            case "unsubscribePreview":
                if let id = values["subscriptionId"] as? String { subscriptions.removeValue(forKey: id) }
                reply(nil)
            case "openCapture":
                let viewId = try ScannerMethodArguments.viewId(values)
                guard scannerDevice.contains(viewId: viewId) else { throw MlKitPluginError.invalidArguments }
                closeSelected()
                let lease = CaptureLease(viewId: viewId)
                selected = lease
                reply(lease.id)
            case "closeCapture":
                if selected?.id == values["captureId"] as? String { closeSelected() }
                reply(nil)
            case "disposeScanner":
                closeSelected()
                scannerDevice.disposeResources { [weak self] in
                    self?.publishPreview(nil)
                    reply(nil)
                }
            case PluginConstants.getIosAvailableCamerasMethod:
                GetIosAvailableCamerasCommand(scannerDevice: scannerDevice).execute(call, result: reply)
            default:
                guard let lease = selected, !lease.closed, lease.id == values["captureId"] as? String else {
                    throw MlKitPluginError.cameraSessionDisposed
                }
                let pending = lease.reply(reply)
                let scoped: FlutterResult = { pending.complete($0) }
                do {
                    switch call.method {
                    case PluginConstants.resumeCameraMethod:
                        try scannerDevice.updateGeometry(viewId: lease.viewId, arguments: values["geometry"])
                        let settings = try ScannerConfiguration(arguments: values["configuration"])
                        scannerDevice.captureCamera(viewId: lease.viewId, configuration: settings) { error in command.complete(scoped, error: error) }
                    case PluginConstants.pauseCameraMethod:
                        scannerDevice.pauseCamera { scoped(nil) }
                    case "updatePreviewGeometry":
                        try scannerDevice.updateGeometry(viewId: lease.viewId, arguments: values)
                        scoped(nil)
                    case "focus":
                        try scannerDevice.focus(locked: PlatformChannelScalar.bool(from: values["locked"]))
                        scoped(nil)
                    case "subscribeScan":
                        try scannerDevice.cancelScan()
                        let endpoint = ScanEndpoint()
                        lease.scan = endpoint
                        scoped(endpoint.id)
                    case PluginConstants.startScanMethod:
                        guard let endpoint = lease.scan, endpoint.id == values["subscriptionId"] as? String else { throw MlKitPluginError.invalidArguments }
                        endpoint.enabled = true
                        StartScanCommand(scannerDevice: scannerDevice).execute(call, result: scoped)
                    case PluginConstants.cancelScanMethod:
                        lease.scan = nil
                        CancelScanCommand(scannerDevice: scannerDevice).execute(call, result: scoped)
                    case PluginConstants.setZoomRatioMethod:
                        SetZoomRatioCommand(scannerDevice: scannerDevice).execute(call, result: scoped)
                    case "updateCameraSettings":
                        UpdateCameraSettingsCommand(scannerDevice: scannerDevice).execute(call, result: scoped)
                    case PluginConstants.toggleFlashMethod:
                        ToggleFlashCommand(scannerDevice: scannerDevice).execute(call, result: scoped)
                    case PluginConstants.setScanDelayMethod:
                        SetScanDelayCommand(scannerDevice: scannerDevice).execute(call, result: scoped)
                    case PluginConstants.setCropAreaMethod:
                        SetCropAreaCommand(scannerDevice: scannerDevice).execute(call, result: scoped)
                    default: scoped(FlutterMethodNotImplemented)
                    }
                } catch { command.reportError(scoped, error: error) }
            }
        } catch { command.reportError(reply, error: error) }
    }

    /// Revokes the selected lease before releasing native ownership.
    private func closeSelected() {
        let previous = selected
        selected = nil
        previous?.close()
        scannerDevice.releaseCamera(completion: {})
    }

    /// Caches preview metadata and broadcasts it to active subscriptions.
    private func publishPreview(_ description: [String: Any]?) {
        preview = description
        guard !isDisposed else { return }
        for subscription in subscriptions.values {
            channel.invokeMethod("onPreviewState", arguments: ["subscriptionId": subscription.id, "description": description as Any? ?? NSNull()])
        }
    }
}

/// Main-thread ownership lifetime for camera commands and barcode delivery.
private final class CaptureLease {
    /// Opaque identifier exposed to Flutter for this concrete lifetime.
    let id = UUID().uuidString
    /// Identifier of the logical Flutter consumer.
    let viewId: Int64
    /// Current barcode endpoint belonging to this capture lease.
    var scan: ScanEndpoint?
    /// Whether this lease has been revoked on main.
    private(set) var closed = false
    /// Unfinished method replies keyed by object identity.
    private var replies: [ObjectIdentifier: PendingReply] = [:]

    /// Creates a capture lifetime for one registered Flutter consumer.
    init(viewId: Int64) {
        self.viewId = viewId
    }

    /// Tracks a response until it completes or the capture lease closes.
    func reply(_ result: @escaping FlutterResult) -> PendingReply {
        let reply = PendingReply(result) { [weak self] reply in self?.replies.removeValue(forKey: ObjectIdentifier(reply)) }
        replies[ObjectIdentifier(reply)] = reply
        return reply
    }

    /// Revokes scan delivery and fails every outstanding response once.
    func close() {
        guard !closed else {
            return
        }
        closed = true
        scan = nil
        let pending = Array(replies.values)
        replies.removeAll()
        for reply in pending { reply.complete(FlutterError(code: MlKitPluginError.cameraSessionDisposed.rawValue,
            message: MlKitPluginError.cameraSessionDisposed.localizedDescription, details: nil)) }
    }
}

/// Main-thread method response removed from its lease before invoking Flutter.
private final class PendingReply {
    /// Flutter response cleared before its first delivery.
    private var result: FlutterResult?
    /// Removes this reply from its owning lease before invoking Flutter.
    private let onComplete: (PendingReply) -> Void

    /// Stores a Flutter response and its ownership-cleanup callback.
    init(_ result: @escaping FlutterResult, onComplete: @escaping (PendingReply) -> Void) {
        self.result = result
        self.onComplete = onComplete
    }

    /// Clears the pending response before invoking it to prevent repeated delivery.
    func complete(_ value: Any?) {
        let reply = result
        result = nil
        onComplete(self)
        reply?(value)
    }
}

/// Barcode subscription identity within one capture lease.
private final class ScanEndpoint {
    /// Opaque identifier exposed to Flutter for this concrete lifetime.
    let id = UUID().uuidString
    /// Whether Flutter has started recognition for this endpoint.
    var enabled = false
}

/// Independent subscription identity for shared preview state.
private final class PreviewSubscription {
    /// Opaque identifier exposed to Flutter for this concrete lifetime.
    let id = UUID().uuidString
}
