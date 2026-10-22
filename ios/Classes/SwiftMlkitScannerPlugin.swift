import Flutter
import UIKit

/// Routes scanner commands and events within engine and capture lifetimes.
public final class SwiftMlkitScannerPlugin: NSObject, FlutterPlugin {
    /// Method channel for this Flutter engine.
    private let channel: FlutterMethodChannel

    /// Texture registry owned by this Flutter engine.
    private let textures: FlutterTextureRegistry

    /// Active capture lease, accessed on main.
    private var selected: CaptureLease?

    /// Preview subscription identifiers independent of capture ownership, accessed on main.
    private var previewSubscriptions: Set<String> = []

    /// Latest shared preview description, cached on main.
    private var preview: [String: Any]?

    /// Whether the plugin has detached from its Flutter engine.
    private var isDisposed = false

    /// Shared native scanner with main-thread lease-aware event routing.
    private lazy var scanner = Scanner(
        onScanResult: { [weak self] viewId, barcode in
            guard let self = self, let lease = self.selected, !lease.closed, lease.viewId == viewId,
                  let subscription = lease.scanSubscription, !subscription.closed, subscription.enabled else { return }
            self.channel.invokeMethod(PluginConstants.scanResultMethod, arguments: [
                "viewId": viewId, "captureId": lease.id, "subscriptionId": subscription.id, "barcode": barcode.toJson()])
        }, onTorchChanged: { [weak self] viewId, enabled in
            guard let self = self, let lease = self.selected, !lease.closed, lease.viewId == viewId else { return }
            self.channel.invokeMethod(PluginConstants.changeTorchStateMethod,
                arguments: ["viewId": viewId, "captureId": lease.id, "value": enabled])
        }, onPreviewChanged: { [weak self] description in
            self?.publishPreview(description)
        }, cameraFactory: { [unowned self] in
            let output: CameraPreviewOutput = CameraTextureOutput(registry: self.textures)
            return CameraPreview(output: output)
        }, analyzerFactory: {
            MlkitBarcodeScanner(delay: 0, cropRect: nil)
        })

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

    /// Encodes native metadata only at the Flutter channel boundary.
    static func previewArguments(_ metadata: CameraPreviewDescription) -> [String: Any] {
        let state: String
        switch metadata.state {
        case .streaming: state = "streaming"
        case .paused: state = "paused"
        }
        return ["textureId": metadata.textureId, "width": metadata.width, "height": metadata.height,
            "rotationDegrees": metadata.rotationDegrees, "mirrored": metadata.mirrored, "state": state]
    }

    /// Revokes active responses and releases resources when the engine detaches.
    public func detachFromEngine(for registrar: FlutterPluginRegistrar) {
        closeSelected()
        isDisposed = true
        previewSubscriptions.removeAll()
        scanner.release()
    }

    /// Routes method calls through the current capture lease and its pending replies.
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard !isDisposed else { return }
        let reply = result
        let command = BaseScannerCommand(scanner: scanner)
        do {
            let values = call.arguments as? [String: Any] ?? [:]
            switch call.method {
            case "registerScanner":
                let viewId = try values.requireInt64(PluginConstants.viewIdArgument)
                guard viewId >= 0 else { throw MlKitPluginError.invalidArguments }
                scanner.register(viewId: viewId)
                reply(preview)
            case "unregisterScanner":
                let viewId = try values.requireInt64(PluginConstants.viewIdArgument)
                guard viewId >= 0 else { throw MlKitPluginError.invalidArguments }
                if selected?.viewId == viewId { closeSelected() }
                scanner.unregister(viewId: viewId)
                reply(nil)
            case "subscribePreview":
                let subscriptionId = UUID().uuidString
                previewSubscriptions.insert(subscriptionId)
                reply(["subscriptionId": subscriptionId, "description": preview as Any? ?? NSNull()])
            case "unsubscribePreview":
                if let id = values["subscriptionId"] as? String { previewSubscriptions.remove(id) }
                reply(nil)
            case "openCapture":
                let viewId = try values.requireInt64(PluginConstants.viewIdArgument)
                guard viewId >= 0 else { throw MlKitPluginError.invalidArguments }
                guard scanner.contains(viewId: viewId) else { throw MlKitPluginError.invalidArguments }
                closeSelected()
                let lease = CaptureLease(viewId: viewId)
                selected = lease
                reply(lease.id)
            case "closeCapture":
                if selected?.id == values["captureId"] as? String { closeSelected() }
                reply(nil)
            case "disposeScanner":
                closeSelected()
                scanner.disposeResources { [weak self] in
                    self?.publishPreview(nil)
                    reply(nil)
                }
            case PluginConstants.getIosAvailableCamerasMethod:
                GetIosAvailableCamerasCommand(scanner: scanner).execute(call, result: reply)
            default:
                guard let lease = selected, !lease.closed, lease.id == values["captureId"] as? String else {
                    throw MlKitPluginError.cameraSessionDisposed
                }
                let pending = lease.reply(reply)
                let scoped: FlutterResult = { pending.complete($0) }
                do {
                    switch call.method {
                    case PluginConstants.resumeCameraMethod:
                        try scanner.updateGeometry(viewId: lease.viewId, size: values.requireMap("geometry").requireSize())
                        let settings = try ScannerConfiguration(arguments: values["configuration"])
                        scanner.captureCamera(viewId: lease.viewId, configuration: settings) { error in command.complete(scoped, error: error) }
                    case PluginConstants.pauseCameraMethod:
                        scanner.pauseCamera { scoped(nil) }
                    case "updatePreviewGeometry":
                        try scanner.updateGeometry(viewId: lease.viewId, size: values.requireSize())
                        scoped(nil)
                    case "focus":
                        try scanner.focus(locked: values.requireBoolean("locked"))
                        scoped(nil)
                    case "subscribeScan":
                        lease.scanSubscription?.close()
                        try scanner.cancelScan()
                        let subscription = ScanSubscription()
                        lease.scanSubscription = subscription
                        scoped(subscription.id)
                    case PluginConstants.startScanMethod:
                        guard let subscription = lease.scanSubscription, !subscription.closed, subscription.id == values["subscriptionId"] as? String else { throw MlKitPluginError.invalidArguments }
                        subscription.enabled = true
                        StartScanCommand(scanner: scanner).execute(call, result: scoped)
                    case PluginConstants.cancelScanMethod:
                        lease.scanSubscription?.close()
                        lease.scanSubscription = nil
                        CancelScanCommand(scanner: scanner).execute(call, result: scoped)
                    case PluginConstants.setZoomRatioMethod:
                        SetZoomRatioCommand(scanner: scanner).execute(call, result: scoped)
                    case "updateCameraSettings":
                        UpdateCameraSettingsCommand(scanner: scanner).execute(call, result: scoped)
                    case PluginConstants.toggleFlashMethod:
                        ToggleFlashCommand(scanner: scanner).execute(call, result: scoped)
                    case PluginConstants.setScanDelayMethod:
                        SetScanDelayCommand(scanner: scanner).execute(call, result: scoped)
                    case PluginConstants.setCropAreaMethod:
                        SetCropAreaCommand(scanner: scanner).execute(call, result: scoped)
                    default: scoped(FlutterMethodNotImplemented)
                    }
                } catch { command.reportError(scoped, error: error) }
            }
        } catch { command.reportError(reply, error: error) }
    }

    /// Releases camera ownership and cancels outstanding capture work.
    private func closeSelected() {
        let previous = selected
        selected = nil
        previous?.close()
        scanner.releaseCamera(completion: {})
    }

    /// Caches preview metadata and broadcasts it to active subscriptions.
    private func publishPreview(_ metadata: CameraPreviewDescription?) {
        let description = metadata.map(Self.previewArguments)
        preview = description
        guard !isDisposed else { return }
        for subscriptionId in Array(previewSubscriptions) where previewSubscriptions.contains(subscriptionId) {
            channel.invokeMethod("onPreviewState", arguments: ["subscriptionId": subscriptionId, "description": description as Any? ?? NSNull()])
        }
    }
}

/// Main-thread ownership lifetime for camera commands and barcode delivery.
private final class CaptureLease {
    /// Unique identifier of this capture lifetime.
    let id = UUID().uuidString
    /// Identifier of the registered camera consumer.
    let viewId: Int64
    /// Barcode subscription owned by this capture lease.
    var scanSubscription: ScanSubscription?
    /// Whether this lease has been revoked on main.
    private(set) var closed = false
    /// Unfinished method replies keyed by object identity.
    private var replies: [ObjectIdentifier: PendingReply] = [:]

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
        scanSubscription?.close()
        scanSubscription = nil
        let pending = Array(replies.values)
        replies.removeAll()
        for reply in pending { reply.complete(FlutterError(code: MlKitPluginError.cameraSessionDisposed.rawValue,
            message: MlKitPluginError.cameraSessionDisposed.localizedDescription, details: nil)) }
    }
}

/// Main-thread method response that delivers its result at most once.
private final class PendingReply {
    /// Response awaiting delivery.
    private var result: FlutterResult?
    /// Completion callback invoked before delivering the response.
    private let onComplete: (PendingReply) -> Void

    init(_ result: @escaping FlutterResult, onComplete: @escaping (PendingReply) -> Void) {
        self.result = result
        self.onComplete = onComplete
    }

    /// Delivers the response at most once, including during reentrant callbacks.
    func complete(_ value: Any?) {
        let reply = result
        result = nil
        onComplete(self)
        reply?(value)
    }
}

/// Barcode subscription with an identity and cancellable delivery state.
private final class ScanSubscription {
    /// Unique identifier of this subscription lifetime.
    let id = UUID().uuidString
    /// Whether barcode delivery is enabled for this subscription.
    var enabled = false
    /// Whether this subscription has been permanently revoked.
    private(set) var closed = false

    /// Permanently disables barcode delivery for this subscription.
    func close() {
        closed = true
        enabled = false
    }
}
