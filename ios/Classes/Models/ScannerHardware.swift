import AVFoundation
import Foundation
import MLKitBarcodeScanning
import UIKit

/// Main-thread SDK bridge. Dart selects the consumer, retains settings and serializes commands.
final class ScannerHardware: ScannerDevice {
    /// Weak entries follow Flutter's platform-view lifetime, without a parallel native state store.
    private let views = NSMapTable<NSNumber, ScannerView>(keyOptions: .strongMemory, valueOptions: .weakMemory)
    /// Borrowed current consumer; Flutter is free to destroy its platform-view wrapper.
    private weak var selected: ScannerView?
    /// Shared physical adapter, allocated only after permission succeeds.
    private var camera: CameraPreviewing?
    /// Reusable ML Kit SDK instance; it owns the current result subscription.
    private var analyzer: MlkitBarcodeScanner?
    /// The actual unfinished response owns capture arguments and permission/layout/start callbacks.
    private var capture: Capture?
    /// Invalidated by a new consumer or terminal teardown.
    private var idleDisposal: Timer?
    /// Terminal engine teardown, distinct from an ordinary consumer release.
    private var isReleased = false
    /// Physical app availability, including messages arriving before Dart observes backgrounding.
    private var isBackgrounded = UIApplication.shared.applicationState == .background
    /// Main-thread transport callbacks; they do not own state or select views.
    private let onScanResult: (Int64, Barcode) -> Void
    private let onTorchChanged: (Int64, Bool) -> Void
    /// Replaceable SDK boundary for hardware-free native tests.
    private let cameraFactory: () -> CameraPreviewing
    /// System permission bridge; cancelling capture withdraws its callback eligibility.
    private let requestPermission: (@escaping (Bool) -> Void) -> Void
    /// Native lifecycle safety does not depend on timely Dart message delivery.
    private let notificationCenter: NotificationCenter
    /// Exact notification registrations removed on terminal teardown.
    private var lifecycleObservers: [NSObjectProtocol] = []

    init(
        onScanResult: @escaping (Int64, Barcode) -> Void,
        onTorchChanged: @escaping (Int64, Bool) -> Void,
        notificationCenter: NotificationCenter = .default,
        cameraFactory: @escaping () -> CameraPreviewing = { CameraPreview(frame: .zero) },
        requestPermission: @escaping (@escaping (Bool) -> Void) -> Void = ScannerHardware.requestCameraPermission
    ) {
        self.onScanResult = onScanResult
        self.onTorchChanged = onTorchChanged
        self.notificationCenter = notificationCenter
        self.cameraFactory = cameraFactory
        self.requestPermission = requestPermission
        // Backgrounding must stop physical capture even before Dart processes its notification.
        lifecycleObservers = [notificationCenter.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.isBackgrounded = true
            self?.releaseConsumer(completion: {})
            self?.disposeHardware()
        }, notificationCenter.addObserver(
            forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main
        ) { [weak self] _ in self?.isBackgrounded = false }]
    }

    deinit { release() }

    func createView(frame: CGRect, viewId: Int64) -> ScannerView {
        precondition(!isReleased && views.object(forKey: NSNumber(value: viewId)) == nil)
        let view = ScannerView(frame: frame, viewId: viewId) { [weak self] view in
            self?.removeView(view)
        }
        views.setObject(view, forKey: NSNumber(value: viewId))
        return view
    }

    func captureCamera(viewId: Int64, configuration: ScannerConfiguration,
                       completion: @escaping ScannerCompletion) {
        guard !isReleased, !isBackgrounded, let target = views.object(forKey: NSNumber(value: viewId)) else {
            completion(nil)
            return
        }
        idleDisposal?.invalidate()
        idleDisposal = nil
        let previous = capture
        stopScan()
        camera?.view().removeFromSuperview()
        selected = target
        let request = Capture(view: target, configuration: configuration, completion: completion)
        capture = request
        previous?.complete(nil)
        guard isCurrent(request) else { return }
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

    func releaseCamera( completion: @escaping () -> Void) {
        guard selected != nil || capture != nil else { completion(); return }
        releaseConsumer(completion: completion)
    }

    func setTorch(enabled: Bool) throws {
        try control(operation: .torch) { try $0.setFlash(enabled) }
    }

    func setZoomRatio(value: Double) throws {
        try control(operation: .zoom) { try $0.setZoomRatio(value) }
    }

    func setCropArea(cropRect: CropRect) throws {
        camera?.setCropArea(cropRect)
        analyzer?.updateCropRect(cropRect: cropRect)
    }

    func updateScanPeriod(delay: Int) throws {
        analyzer?.setDelay(delay: delay)
    }

    func startScan(type: RecognitionType, delay: Int) throws {
        beginScan(delay: delay)
    }

    func cancelScan() throws { stopScan() }

    /// Clears ownership before callbacks; a late SDK response cannot reach the detached engine.
    func release() {
        guard !isReleased else { return }
        isReleased = true
        lifecycleObservers.forEach(notificationCenter.removeObserver)
        lifecycleObservers.removeAll()
        selected = nil
        cancelCapture(error: MlKitPluginError.cameraSessionDisposed)
        disposeHardware()
        views.removeAllObjects()
    }

    /// Flutter can deallocate the wrapper before weak registry/selection references can be read.
    private func removeView(_ view: ScannerView) {
        guard !isReleased else { return }
        let key = NSNumber(value: view.viewId)
        if let replacement = views.object(forKey: key), replacement !== view { return }
        views.removeObject(forKey: key)
        // Swift clears weak references before deinit. If no owner survives and no
        // capture is still pending, its response must still be cancelled.
        let ownerWasDeallocated = selected == nil && capture != nil
        if selected === view || ownerWasDeallocated || camera?.view().superview === view.view() {
            releaseConsumer(completion: {})
        }
    }

    /// Permission is checked before creating SDK resources, and again through actual request ownership.
    private func prepareCamera(_ request: Capture) {
        guard let target = request.view else { finish(request, error: nil); return }
        if let camera = camera {
            camera.view().isHidden = true
            target.attach(camera.view())
            activate(request, camera: camera)
            return
        }
        let device = cameraFactory()
        camera = device
        device.cameraPreviewDelegate = self
        target.attach(device.view())
        device.view().isHidden = true
        device.initCamera { [weak self, weak request, weak device] error in
            Self.onMain {
                guard let self = self, let request = request, let device = device,
                      self.isCurrent(request), self.camera === device else { return }
                if let error = error {
                    self.disposeHardware()
                    self.finish(request, error: error)
                } else {
                    self.activate(request, camera: device)
                }
            }
        }
    }

    /// Only this unfinished call owns the snapshot. No native copy survives capture completion.
    private func activate(_ request: Capture, camera: CameraPreviewing) {
        do {
            let settings = request.configuration
            try camera.setCamera(settings.camera)
            camera.setCropArea(settings.cropRect)
            camera.resetFocus()
            try control(operation: .zoom) { try $0.setZoomRatio(settings.zoomRatio) }
            try control(operation: .torch) { try $0.setFlash(settings.torchEnabled) }
            if analyzer == nil { analyzer = MlkitBarcodeScanner(delay: settings.scanDelay, cropRect: settings.cropRect) }
            analyzer?.updateCropRect(cropRect: settings.cropRect)
            camera.whenLayoutReady { [weak self, weak request, weak camera] in
                guard let self = self, let request = request, let camera = camera, self.isCurrent(request) else { return }
                camera.resumeCamera { error in
                    Self.onMain {
                        guard self.isCurrent(request) else { return }
                        if error == nil {
                            camera.view().isHidden = false
                            if request.configuration.scanEnabled { self.beginScan(delay: request.configuration.scanDelay) }
                        }
                        let failure = error.map { self.controlError(.awaitOpen, viewId: request.view?.viewId, error: $0) }
                        self.finish(request, error: failure)
                        if error == nil, !self.isReleased, self.capture == nil,
                           self.selected === request.view, let view = request.view {
                            self.onTorchChanged(view.viewId, camera.isTorchActive)
                        }
                    }
                }
            }
        } catch { finish(request, error: error) }
    }

    /// Release interrupts layout/first-frame waits and gives the next consumer 300 ms to return.
    private func releaseConsumer(completion: @escaping () -> Void) {
        let previous = capture
        capture = nil
        selected = nil
        // Reply only after all cancellation work has been submitted to the SDK queue.
        defer { previous?.complete(nil) }
        stopScan()
        camera?.view().removeFromSuperview()
        idleDisposal?.invalidate()
        idleDisposal = Timer.scheduledTimer(withTimeInterval: 0.3, repeats: false) { [weak self] _ in
            self?.disposeHardware()
        }
        guard let camera = camera else { completion(); return }
        // A partially configured adapter is not reusable after cancellation.
        if !camera.isInitialized { disposeHardware() }
        camera.cancelPendingStart { Self.onMain(completion) }
    }

    /// Unsubscribing first prevents an in-flight ML Kit callback from being reassigned to the next view.
    private func stopScan() {
        analyzer?.unsubscribe()
        camera?.recognitionHandler = nil
        camera?.setScanActive(false)
    }

    /// Replaces the analysis listener without rebuilding the SDK recognizer.
    private func beginScan(delay: Int) {
        guard let target = selected, let analyzer = analyzer, let camera = camera else { return }
        analyzer.setDelay(delay: delay)
        _ = analyzer.subscribe { [weak self, weak target] barcode in
            guard let self = self, let target = target, self.selected === target, !self.isReleased else { return }
            self.onScanResult(target.viewId, barcode)
        }
        camera.recognitionHandler = analyzer
        camera.setScanActive(true)
    }

    /// Dart admits commands; this boundary only translates SDK failures.
    private func control(operation: CameraControlOperation,
                         action: (CameraPreviewing) throws -> Void) throws {
        guard let camera = camera else { return }
        let viewId = selected?.viewId
        do { try action(camera) }
        catch MlKitPluginError.deviceHasNotFlash { throw MlKitPluginError.deviceHasNotFlash }
        catch { throw controlError(operation, viewId: viewId, error: error) }
    }

    /// Keeps structured SDK diagnostics when adding the addressed operation context.
    private func controlError(_ operation: CameraControlOperation, viewId: Int64?, error: Error) -> CameraControlError {
        if let previous = error as? CameraControlError {
            return CameraControlError(operation: operation, viewId: viewId,
                underlyingError: previous.underlyingError, cameraStateErrorCode: previous.cameraStateErrorCode)
        }
        return CameraControlError(operation: operation, viewId: viewId, underlyingError: error)
    }

    /// Releases only native resources; surviving Flutter views can later capture again.
    private func disposeHardware() {
        idleDisposal?.invalidate()
        idleDisposal = nil
        stopScan()
        let previous = camera
        camera = nil
        analyzer = nil
        previous?.view().removeFromSuperview()
        previous?.dispose()
    }

    /// Every deferred callback must still belong to the actual pending response and live view.
    private func isCurrent(_ request: Capture) -> Bool {
        !isReleased && capture === request && request.view != nil && selected === request.view
    }

    /// Withdraws the pending response before invoking the method-channel completion.
    private func finish(_ request: Capture, error: Error?) {
        guard capture === request else { return }
        capture = nil
        request.complete(error)
    }

    /// Completes an obsolete request without allowing its later SDK callback to complete it again.
    private func cancelCapture(error: Error? = nil) {
        let previous = capture
        capture = nil
        previous?.complete(error)
    }

    /// Bridges legacy callback queues; all runtime state and Flutter replies remain main-thread confined.
    private static func onMain(_ action: @escaping () -> Void) {
        if Thread.isMainThread { action() } else { DispatchQueue.main.async(execute: action) }
    }

    /// OS dialogs cannot be dismissed by cancelling a scanner capture.
    private static func requestCameraPermission(_ completion: @escaping (Bool) -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: completion(true)
        case .notDetermined: AVCaptureDevice.requestAccess(for: .video, completionHandler: completion)
        default: completion(false)
        }
    }

    /// A real pending response, not an identity-only marker or a counter.
    private final class Capture {
        weak var view: ScannerView?
        let configuration: ScannerConfiguration
        /// Nil after this real channel response has been consumed.
        private var completion: ScannerCompletion?

        init(view: ScannerView, configuration: ScannerConfiguration, completion: @escaping ScannerCompletion) {
            self.view = view
            self.configuration = configuration
            self.completion = completion
        }

        func complete(_ error: Error?) {
            let reply = completion
            completion = nil
            reply?(error)
        }
    }
}

extension ScannerHardware: CameraPreviewDelegate {
    func onTorchChanged(_ camera: CameraPreviewing, enabled: Bool) {
        guard self.camera === camera, capture == nil, !isReleased, let view = selected else { return }
        onTorchChanged(view.viewId, enabled)
    }
    func canApplyFocus() -> Bool { selected != nil && capture == nil && !isReleased }
}
