import AVFoundation
import Foundation
import UIKit

/// Main-thread ownership bridge. Camera operations are serialized by the camera adapter.
final class ScannerHardware: ScannerDevice {
    /// Registered logical Flutter consumers keyed by view identifier.
    private var consumers: [Int64: ScannerConsumer] = [:]
    /// Consumer currently owning camera controls on main.
    private var selected: ScannerConsumer?
    /// Shared native camera retained across ownership handoffs.
    private var camera: CameraPreviewing?
    /// Reusable recognizer attached to the active scan subscription.
    private var analyzer: BarcodeAnalyzing?
    /// Current capture request, validated by object identity.
    private var capture: Capture?
    /// In-flight camera disposal and callbacks waiting for it to finish.
    private var disposal: HardwareDisposal?
    /// Whether this hardware bridge has permanently released its resources.
    private var isReleased = false
    /// Whether application lifecycle currently prevents capture.
    private var isBackgrounded = UIApplication.shared.applicationState == .background
    /// Main-thread barcode callback scoped to the owning consumer.
    private let onScanResult: (Int64, ScannerBarcode) -> Void
    /// Main-thread torch callback scoped to the owning consumer.
    private let onTorchChanged: (Int64, Bool) -> Void
    /// Publishes preview state only from the currently owned camera.
    private let onPreviewChanged: ([String: Any]?) -> Void
    /// Creates the shared native session adapter when needed.
    private let cameraFactory: () -> CameraPreviewing
    /// Creates the recognition backend when the first capture is prepared.
    private let analyzerFactory: () -> BarcodeAnalyzing
    /// Requests camera permission before admitting a capture.
    private let requestPermission: (@escaping (Bool) -> Void) -> Void
    /// Notification center used for application lifecycle observation.
    private let notificationCenter: NotificationCenter
    /// Lifecycle tokens removed when this bridge is released.
    private var observers: [NSObjectProtocol] = []

    /// Installs lifecycle observers and collaborators for shared camera ownership.
    init(onScanResult: @escaping (Int64, ScannerBarcode) -> Void,
         onTorchChanged: @escaping (Int64, Bool) -> Void,
         onPreviewChanged: @escaping ([String: Any]?) -> Void = { _ in },
         notificationCenter: NotificationCenter = .default,
         cameraFactory: @escaping () -> CameraPreviewing,
         analyzerFactory: @escaping () -> BarcodeAnalyzing = {
             MlkitBarcodeScanner(delay: 0, cropRect: nil)
         },
         requestPermission: @escaping (@escaping (Bool) -> Void) -> Void = ScannerHardware.requestCameraPermission) {
        self.onScanResult = onScanResult
        self.onTorchChanged = onTorchChanged
        self.onPreviewChanged = onPreviewChanged
        self.cameraFactory = cameraFactory
        self.analyzerFactory = analyzerFactory
        self.requestPermission = requestPermission
        self.notificationCenter = notificationCenter
        observers = [notificationCenter.addObserver(forName: UIApplication.didEnterBackgroundNotification,
            object: nil, queue: .main) { [weak self] _ in
                self?.isBackgrounded = true
                self?.pauseCamera(completion: {})
            }, notificationCenter.addObserver(forName: UIApplication.willEnterForegroundNotification,
            object: nil, queue: .main) { [weak self] _ in self?.isBackgrounded = false }]
    }

    /// Removes any lifecycle observers still registered at deallocation.
    deinit {
        observers.forEach(notificationCenter.removeObserver)
    }

    /// Creates a logical consumer if its view identifier is not registered.
    func register(viewId: Int64) {
        if consumers[viewId] == nil {
            consumers[viewId] = ScannerConsumer(viewId)
        }
    }

    /// Releases ownership before removing a registered consumer.
    func unregister(viewId: Int64) {
        if selected === consumers[viewId] { releaseCamera(completion: {}) }
        consumers.removeValue(forKey: viewId)
    }

    /// Whether a logical Flutter consumer is registered.
    func contains(viewId: Int64) -> Bool {
        consumers[viewId] != nil
    }

    /// Updates the viewport used to map preview coordinates into camera frames.
    func updateGeometry(viewId: Int64, arguments: Any?) throws {
        guard let consumer = consumers[viewId], let values = arguments as? [String: Any] else { throw MlKitPluginError.invalidArguments }
        let width = try PlatformChannelScalar.number(from: values["width"]).doubleValue
        let height = try PlatformChannelScalar.number(from: values["height"]).doubleValue
        guard width.isFinite, height.isFinite, width > 0, height > 0 else { throw MlKitPluginError.invalidArguments }
        consumer.size = CGSize(width: width, height: height)
        if selected === consumer { camera?.updateGeometry(consumer.size) }
    }

    /// Acquires the shared camera for a registered consumer using its settings.
    func captureCamera(viewId: Int64, configuration: ScannerConfiguration, completion: @escaping ScannerCompletion) {
        guard !isReleased, !isBackgrounded, let target = consumers[viewId] else {
            completion(MlKitPluginError.cameraSessionDisposed)
            return
        }
        releaseCamera(completion: {})
        selected = target
        let request = Capture(consumer: target, configuration: configuration, completion: completion)
        capture = request
        requestPermission { [weak self, weak request] granted in
            Self.onMain {
                guard let self = self, let request = request, self.isCurrent(request) else { return }
                guard granted else {
                    self.finish(request, error: MlKitPluginError.authorizationCameraError)
                    return
                }
                self.prepareCamera(request)
            }
        }
    }

    /// Revokes the owner immediately; the shared texture and running camera survive a handoff.
    func releaseCamera(completion: @escaping () -> Void) {
        selected = nil
        let previous = capture
        capture = nil
        stopScan()
        camera?.cancelPendingStart(completion: {})
        previous?.complete(MlKitPluginError.cameraSessionDisposed)
        completion()
    }

    /// Stops capture while retaining the most recent texture frame.
    func pauseCamera(completion: @escaping () -> Void) {
        releaseCamera(completion: {})
        guard let camera = camera else {
            completion()
            return
        }
        camera.pauseCamera(completion: completion)
    }

    /// Applies autofocus and exposure modes at the recognition-area center.
    func focus(locked: Bool) throws {
        try control(operation: .focus) {
            try $0.focus(locked: locked)
        }
    }

    /// Applies the desired torch state to the active camera.
    func setTorch(enabled: Bool) throws {
        try control(operation: .torch) {
            try $0.setFlash(enabled)
        }
    }

    /// Applies an absolute zoom factor to the selected camera.
    func setZoomRatio(value: Double) throws {
        try control(operation: .zoom) {
            try $0.setZoomRatio(value)
        }
    }

    /// Updates the normalized area used for focus and recognition.
    func setCropArea(cropRect: CropRect) throws {
        camera?.setCropArea(cropRect)
        analyzer?.updateCropRect(cropRect: cropRect)
    }

    /// Changes the cooldown after successful barcode recognition.
    func updateScanPeriod(delay: Int) throws {
        analyzer?.setDelay(delay: delay)
    }

    /// Enables barcode delivery with the requested recognition cooldown.
    func startScan(type: RecognitionType, delay: Int) throws {
        beginScan(delay: delay)
    }

    /// Cancels the current barcode subscription and suppresses queued deliveries.
    func cancelScan() throws {
        stopScan()
    }

    /// Coalesces disposal callbacks while releasing the shared camera and recognizer.
    func disposeResources(completion: @escaping () -> Void) {
        releaseCamera(completion: {})
        if let disposal = disposal {
            disposal.callbacks.append(completion)
            return
        }
        let previous = camera
        camera = nil
        analyzer = nil
        guard let previous = previous else {
            onPreviewChanged(nil)
            completion()
            return
        }
        dispose(previous, completion: completion)
    }

    /// Permanently releases scanner ownership, observers, and camera resources.
    func release() {
        guard !isReleased else { return }
        isReleased = true
        observers.forEach(notificationCenter.removeObserver)
        observers.removeAll()
        disposeResources(completion: {})
        consumers.removeAll()
    }

    /// Waits for stale camera disposal before initializing or reusing a session.
    private func prepareCamera(_ request: Capture) {
        if let disposal = disposal {
            disposal.callbacks.append { [weak self, weak request] in
                guard let self = self, let request = request, self.isCurrent(request) else { return }
                self.prepareCamera(request)
            }
            return
        }
        if let camera = camera, camera.isInitialized {
            activate(request, camera: camera)
            return
        }
        // An incomplete initialization belongs to the previous request and cannot be reused.
        if let previous = camera {
            camera = nil
            dispose(previous) { [weak self, weak request] in
                guard let self = self, let request = request, self.isCurrent(request) else { return }
                self.prepareCamera(request)
            }
            return
        }
        let device = cameraFactory()
        camera = device
        device.cameraPreviewDelegate = self
        device.initCamera { [weak self, weak request, weak device] error in
            Self.onMain {
                guard let self = self, let request = request, let device = device,
                      self.isCurrent(request), self.camera === device else { return }
                if let error = error { self.finish(request, error: error) }
                else { self.activate(request, camera: device) }
            }
        }
    }

    /// Tracks one camera disposal until every waiting callback has been drained.
    private func dispose(_ camera: CameraPreviewing, completion: @escaping () -> Void) {
        let operation = HardwareDisposal()
        disposal = operation
        operation.callbacks.append(completion)
        onPreviewChanged(nil)
        camera.dispose { [weak self] in
            guard let self = self else { return }
            if self.disposal === operation { self.disposal = nil }
            let callbacks = operation.callbacks
            operation.callbacks.removeAll()
            callbacks.forEach { $0() }
        }
    }

    /// Applies capture settings before awaiting the selected stream first frame.
    private func activate(_ request: Capture, camera: CameraPreviewing) {
        camera.prepare(request.configuration, geometry: request.consumer.size) { [weak self, weak request, weak camera] error in
            guard let self = self, let request = request, let camera = camera, self.isCurrent(request) else { return }
            if let error = error {
                let failure: Error
                if let control = error as? CameraControlError {
                    failure = CameraControlError(operation: control.operation, viewId: request.consumer.viewId,
                        underlyingError: control.underlyingError, cameraStateErrorCode: control.cameraStateErrorCode)
                } else { failure = error }
                self.finish(request, error: failure)
                return
            }
            if self.analyzer == nil { self.analyzer = self.analyzerFactory() }
            self.analyzer?.updateCropRect(cropRect: request.configuration.cropRect)
            camera.resumeCamera { [weak self, weak request, weak camera] error in
                guard let self = self, let request = request, let camera = camera, self.isCurrent(request) else { return }
                let failure = error.map { self.controlError(.awaitOpen, error: $0) }
                self.finish(request, error: failure)
                if error == nil, self.selected === request.consumer { self.onTorchChanged(request.consumer.viewId, camera.isTorchActive) }
            }
        }
    }

    /// Revokes the result subscription before detaching frame recognition.
    private func stopScan() {
        analyzer?.unsubscribe()
        camera?.recognitionHandler = nil
    }

    /// Installs recognition delivery bound to the current consumer identity.
    private func beginScan(delay: Int) {
        guard let consumer = selected, let analyzer = analyzer, let camera = camera else { return }
        analyzer.setDelay(delay: delay)
        let listener = analyzer.subscribe { [weak self, weak consumer] barcode in
            guard let self = self, let consumer = consumer, self.selected === consumer, !self.isReleased else { return }
            self.onScanResult(consumer.viewId, barcode)
        }
        camera.recognitionHandler = analyzer.input(for: listener)
    }

    /// Runs a selected-camera control with consistent error context.
    private func control(operation: CameraControlOperation, action: (CameraPreviewing) throws -> Void) throws {
        guard selected != nil, !isBackgrounded, let camera = camera else { throw MlKitPluginError.cameraIsNotInitialized }
        do { try action(camera) }
        catch MlKitPluginError.deviceHasNotFlash { throw MlKitPluginError.deviceHasNotFlash }
        catch { throw controlError(operation, error: error) }
    }

    /// Attaches the current consumer and failed operation to a native error.
    private func controlError(_ operation: CameraControlOperation, error: Error) -> CameraControlError {
        CameraControlError(operation: operation, viewId: selected?.viewId, underlyingError: error)
    }

    /// Checks that a capture still belongs to the selected live consumer.
    private func isCurrent(_ request: Capture) -> Bool {
        !isReleased && capture === request && selected === request.consumer
    }

    /// Clears the current capture before completing its response.
    private func finish(_ request: Capture, error: Error?) {
        guard capture === request else {
            return
        }
        capture = nil
        request.complete(error)
    }

    /// Runs immediately on main or dispatches the action there.
    private static func onMain(_ action: @escaping () -> Void) {
        if Thread.isMainThread { action() } else { DispatchQueue.main.async(execute: action) }
    }

    /// Returns current permission or requests access when still undetermined.
    private static func requestCameraPermission(_ completion: @escaping (Bool) -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: completion(true)
        case .notDetermined: AVCaptureDevice.requestAccess(for: .video, completionHandler: completion)
        default: completion(false)
        }
    }

    /// One capture request whose identity remains stable across asynchronous callbacks.
    private final class Capture {
        /// Concrete consumer identity owning this capture request.
        let consumer: ScannerConsumer
        /// Immutable settings captured when ownership was requested.
        let configuration: ScannerConfiguration
        /// Pending capture response cleared before delivery.
        private var completion: ScannerCompletion?

        /// Stores the consumer, capture settings, and pending completion.
        init(consumer: ScannerConsumer, configuration: ScannerConfiguration, completion: @escaping ScannerCompletion) {
            self.consumer = consumer
            self.configuration = configuration
            self.completion = completion
        }

        /// Clears the pending response before invoking it to prevent repeated delivery.
        func complete(_ error: Error?) {
            let reply = completion
            completion = nil
            reply?(error)
        }
    }
}

/// Logical Flutter consumer retaining its latest viewport between captures.
private final class ScannerConsumer {
    /// Identifier of the logical Flutter consumer.
    let viewId: Int64
    /// Latest positive Flutter viewport dimensions for this consumer.
    var size = CGSize(width: 1, height: 1)

    /// Creates a logical consumer with a default viewport.
    init(_ viewId: Int64) {
        self.viewId = viewId
    }
}

/// Coalesces work waiting for one shared camera disposal.
private final class HardwareDisposal {
    /// Main-thread callbacks drained after native camera disposal finishes.
    var callbacks: [() -> Void] = []
}
extension ScannerHardware: CameraPreviewDelegate {
    /// Rejects late publications from a released or replaced camera.
    func onPreviewChanged(_ camera: CameraPreviewing, description: [String: Any]?) {
        guard self.camera === camera, !isReleased else { return }
        onPreviewChanged(description)
    }

    /// Forwards torch changes only from the initialized camera of the current owner.
    func onTorchChanged(_ camera: CameraPreviewing, enabled: Bool) {
        guard self.camera === camera, capture == nil, !isReleased, let consumer = selected else { return }
        onTorchChanged(consumer.viewId, enabled)
    }

    /// Whether an active owner is ready for focus control.
    func canApplyFocus() -> Bool {
        selected != nil && capture == nil && !isReleased
    }
}
