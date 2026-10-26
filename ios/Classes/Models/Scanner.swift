import AVFoundation
import Foundation
import UIKit

/// Coordinates camera ownership, recognition and resource lifetime on the main thread.
/// Suspends camera work while the application is backgrounded.
final class Scanner {
    /// One capture request whose identity remains stable across asynchronous callbacks.
    private final class Capture {
        /// Concrete consumer identity owning this capture request.
        let consumer: ScannerConsumer
        /// Immutable settings captured when ownership was requested.
        let configuration: ScannerConfiguration
        /// Capture response awaiting completion.
        private var completion: ((Error?) -> Void)?

        init(consumer: ScannerConsumer, configuration: ScannerConfiguration, completion: @escaping (Error?) -> Void) {
            self.consumer = consumer
            self.configuration = configuration
            self.completion = completion
        }

        /// Delivers the response at most once, including during reentrant callbacks.
        func complete(_ error: Error?) {
            let reply = completion
            completion = nil
            reply?(error)
        }
    }

    /// Consumer currently owning camera controls on main.
    private var selected: ScannerConsumer?

    /// Shared native camera retained across ownership handoffs.
    private var camera: CameraPreviewing?

    /// Reusable recognizer attached to the active scan subscription.
    private var analyzer: BarcodeAnalyzing?

    /// Current camera capture request.
    private var capture: Capture?

    /// Last acknowledged settings of the shared camera.
    private var appliedConfiguration: ScannerConfiguration?
    /// Preview geometry acknowledged with the camera settings.
    private var appliedGeometry: CGSize?
    /// Whether a gesture requires restoring capture autofocus.
    private var focusNeedsReset = true

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
    private let onPreviewChanged: (CameraPreviewDescription?) -> Void

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

    init(onScanResult: @escaping (Int64, ScannerBarcode) -> Void,
         onTorchChanged: @escaping (Int64, Bool) -> Void,
         onPreviewChanged: @escaping (CameraPreviewDescription?) -> Void = { _ in },
         notificationCenter: NotificationCenter = .default,
         cameraFactory: @escaping () -> CameraPreviewing,
         analyzerFactory: @escaping () -> BarcodeAnalyzing,
         requestPermission: @escaping (@escaping (Bool) -> Void) -> Void = Scanner.requestCameraPermission) {
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

    /// Updates the preview geometry of the current camera owner.
    func updateGeometry(viewId: Int64, size: CGSize) throws {
        guard let consumer = selected, consumer.viewId == viewId else { throw MlKitPluginError.cameraSessionDisposed }
        if appliedGeometry != size { focusNeedsReset = true }
        consumer.size = size
        camera?.updateGeometry(size)
        appliedGeometry = size
    }

    /// Acquires the shared camera with the supplied settings and preview geometry.
    func captureCamera(viewId: Int64, configuration: ScannerConfiguration, geometry: CGSize = CGSize(width: 1, height: 1), completion: @escaping (Error?) -> Void) {
        guard !isReleased, !isBackgrounded else {
            completion(MlKitPluginError.cameraSessionDisposed)
            return
        }
        releaseCapture(completion: {})
        let target = ScannerConsumer(viewId)
        target.size = geometry
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
    func releaseCapture(completion: @escaping () -> Void) {
        guard selected != nil || capture != nil else { completion(); return }
        selected = nil
        let previous = capture
        if previous != nil { appliedConfiguration = nil }
        capture = nil
        stopScan()
        camera?.cancelPendingStart(completion: {})
        previous?.complete(MlKitPluginError.cameraSessionDisposed)
        completion()
    }

    /// Stops capture while retaining the most recent texture frame.
    func pauseCamera(completion: @escaping () -> Void) {
        appliedConfiguration = nil
        releaseCapture(completion: {})
        guard let camera = camera else {
            completion()
            return
        }
        camera.pauseCamera(completion: completion)
    }

    /// Applies autofocus and exposure modes at the recognition-area center.
    func focus(locked: Bool) throws {
        try control {
            try $0.focus(locked: locked)
        }
        focusNeedsReset = true
    }

    /// Applies the desired torch state to the active camera.
    func setTorch(enabled: Bool) throws {
        try control {
            try $0.setFlash(enabled)
        }
        appliedConfiguration?.torchEnabled = enabled
    }

    /// Applies an absolute zoom factor to the selected camera.
    func setZoomRatio(value: Double) throws {
        try control {
            try $0.setZoomRatio(value)
        }
        appliedConfiguration?.zoomRatio = value
    }

    /// Updates the normalized area used for focus and recognition.
    func setCropArea(cropRect: CropRect) throws {
        if appliedConfiguration?.cropRect != cropRect { focusNeedsReset = true }
        camera?.setCropArea(cropRect)
        analyzer?.updateCropRect(cropRect: cropRect)
        appliedConfiguration?.cropRect = cropRect
    }

    /// Changes the cooldown after successful barcode recognition.
    func updateScanPeriod(delay: Int) throws {
        analyzer?.setDelay(delay: delay)
    }

    /// Enables barcode delivery with the requested recognition cooldown.
    func startScan(delay: Int) throws {
        beginScan(delay: delay)
    }

    /// Cancels the current barcode subscription and suppresses queued deliveries.
    func cancelScan() throws {
        stopScan()
    }

    /// Releases camera and recognition resources, notifying all waiting callers.
    func disposeResources(completion: @escaping () -> Void) {
        releaseCapture(completion: {})
        if let disposal = disposal {
            disposal.callbacks.append(completion)
            return
        }
        let previous = camera
        camera = nil
        analyzer = nil
        appliedConfiguration = nil
        appliedGeometry = nil
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
    }

    /// Prepares the camera for the requested capture.
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

    /// Releases the camera and notifies callers when disposal completes.
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

    /// Starts capture with the requested settings, completing when preview is ready.
    private func activate(_ request: Capture, camera: CameraPreviewing) {
        let prepared: (Error?) -> Void = { [weak self, weak request, weak camera] error in
            guard let self = self, let request = request, let camera = camera, self.isCurrent(request) else { return }
            if let error = error {
                self.appliedConfiguration = nil
                let failure: Error
                if let cameraError = error as? CameraError {
                    failure = cameraError.contextualize(viewId: request.consumer.viewId)
                } else { failure = error }
                self.finish(request, error: failure)
                return
            }
            self.appliedConfiguration = request.configuration
            self.appliedGeometry = request.consumer.size
            self.focusNeedsReset = false
            if self.analyzer == nil { self.analyzer = self.analyzerFactory() }
            self.analyzer?.updateCropRect(cropRect: request.configuration.cropRect)
            camera.resumeCamera { [weak self, weak request, weak camera] error in
                guard let self = self, let request = request, let camera = camera, self.isCurrent(request) else { return }
                let failure = error.map { error -> Error in
                    if error is MlKitPluginError { return error }
                    if let cameraError = error as? CameraError {
                        return cameraError.contextualize(viewId: request.consumer.viewId)
                    }
                    return CameraError(message: "Failed to start camera preview", viewId: request.consumer.viewId, underlyingError: error)
                }
                if error != nil { self.appliedConfiguration = nil }
                self.finish(request, error: failure)
                if error == nil, self.selected === request.consumer { self.onTorchChanged(request.consumer.viewId, camera.isTorchActive) }
            }
        }
        prepare(request, camera: camera, completion: prepared)
    }

    /// Prepares the camera, applying only settings that differ from acknowledged values.
    private func prepare(_ request: Capture, camera: CameraPreviewing, completion: @escaping (Error?) -> Void) {
        guard let previous = appliedConfiguration, previous.camera == request.configuration.camera else {
            appliedConfiguration = nil
            camera.prepare(request.configuration, geometry: request.consumer.size, completion: completion)
            return
        }
        let next = request.configuration
        let geometryChanged = appliedGeometry != request.consumer.size
        appliedConfiguration = nil
        do {
            if geometryChanged { camera.updateGeometry(request.consumer.size) }
            if previous.cropRect != next.cropRect { camera.setCropArea(next.cropRect) }
            if previous.zoomRatio != next.zoomRatio { try camera.setZoomRatio(next.zoomRatio) }
            if previous.torchEnabled != next.torchEnabled || camera.isTorchActive != next.torchEnabled { try camera.setFlash(next.torchEnabled) }
            if focusNeedsReset || geometryChanged || previous.cropRect != next.cropRect { camera.resetFocus() }
            completion(nil)
        } catch { completion(error) }
    }

    /// Stops recognition and cancels outstanding result delivery.
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
    private func control(_ action: (CameraPreviewing) throws -> Void) throws {
        guard selected != nil, !isBackgrounded, let camera = camera else { throw MlKitPluginError.cameraIsNotInitialized }
        do { try action(camera) }
        catch let error as MlKitPluginError { appliedConfiguration = nil; throw error }
        catch let error as CameraError { appliedConfiguration = nil; throw error.contextualize(viewId: selected?.viewId) }
        catch { appliedConfiguration = nil; throw CameraError(message: "Camera operation failed", viewId: selected?.viewId, underlyingError: error) }
    }

    /// Checks that a capture still belongs to the selected live consumer.
    private func isCurrent(_ request: Capture) -> Bool {
        !isReleased && capture === request && selected === request.consumer
    }

    /// Completes the current capture without admitting a second response during callback reentry.
    private func finish(_ request: Capture, error: Error?) {
        guard capture === request else {
            return
        }
        capture = nil
        request.complete(error)
    }

    /// Removes any lifecycle observers still registered at deallocation.
    deinit {
        observers.forEach(notificationCenter.removeObserver)
    }
}

/// Current camera consumer and its preview geometry.
private final class ScannerConsumer {
    /// Logical identity of this camera consumer.
    let viewId: Int64
    /// Latest positive preview viewport dimensions for this consumer.
    var size = CGSize(width: 1, height: 1)

    init(_ viewId: Int64) {
        self.viewId = viewId
    }
}

/// Coalesces work waiting for one shared camera disposal.
private final class HardwareDisposal {
    /// Main-thread callbacks drained after native camera disposal finishes.
    var callbacks: [() -> Void] = []
}
extension Scanner: CameraPreviewDelegate {
    /// Rejects late publications from a released or replaced camera.
    func onPreviewChanged(_ camera: CameraPreviewing, description: CameraPreviewDescription?) {
        guard self.camera === camera, !isReleased else { return }
        onPreviewChanged(description)
    }

    /// Updates the shared camera torch state and notifies its ready owner.
    func onTorchChanged(_ camera: CameraPreviewing, enabled: Bool) {
        guard self.camera === camera, !isReleased else { return }
        appliedConfiguration?.torchEnabled = enabled
        guard capture == nil, let consumer = selected else { return }
        onTorchChanged(consumer.viewId, enabled)
    }

    /// Whether an active owner is ready for focus control.
    func canApplyFocus() -> Bool {
        selected != nil && capture == nil && !isReleased
    }
}
