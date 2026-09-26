import AVFoundation
import UIKit

/// Serial AVFoundation session, independently of the Flutter texture's consumer widgets.
final class CameraPreview: NSObject, CameraPreviewing {
    /// Shared Flutter texture receiving frames on main.
    private let output: CameraPreviewOutput
    /// Serial owner of AVFoundation session configuration and camera controls.
    private let queue = DispatchQueue(label: "mlkit_scanner.camera_session", qos: .userInitiated)
    /// Serial sample-buffer delivery queue; recognition uses its own receiver queue.
    private let analysisQueue = DispatchQueue(label: "mlkit_scanner.analysis", qos: .userInitiated)
    /// Detects reentry to the session queue before synchronous dispatch.
    private let queueKey = DispatchSpecificKey<Void>()
    /// Capture session accessed on the session queue.
    private var session: AVCaptureSession?
    /// Selected capture device accessed on the session queue.
    private var camera: AVCaptureDevice?
    /// Video output configured on the session queue.
    private var videoOutput: AVCaptureVideoDataOutput?
    /// Receiver storage protected by lifetimeLock.
    private var frameReceiver: CameraFrameReceiver?
    /// Lock-protected access to the current stream receiver.
    private var receiver: CameraFrameReceiver? {
        get {
            lifetimeLock.lock()
            defer {
                lifetimeLock.unlock()
            }
            return frameReceiver
        }
        set {
            lifetimeLock.lock()
            frameReceiver = newValue
            lifetimeLock.unlock()
        }
    }
    /// First-frame response owned by the main thread.
    private var pendingStart: CameraStart?
    /// Current configuration response owned by the main thread.
    private var preparation: CameraPreparation?
    /// Protects lifetime and endpoint state crossing main and session queues.
    private let lifetimeLock = NSLock()
    /// Disposal flag accessed only through the locked closed property.
    private var isClosed = false
    /// Lock-protected disposal state visible to queued callbacks.
    private var closed: Bool {
        get {
            lifetimeLock.lock()
            defer {
                lifetimeLock.unlock()
            }
            return isClosed
        }
        set {
            lifetimeLock.lock()
            isClosed = newValue
            lifetimeLock.unlock()
        }
    }
    /// Current viewport geometry owned by the session queue.
    private var viewport = CGSize(width: 1, height: 1)
    /// Current normalized focus area owned by the session queue.
    private var crop = CropRect()
    /// Physical video orientation applied on the session queue.
    private var orientation: AVCaptureVideoOrientation = .portrait
    /// Torch observation installed and invalidated on main.
    private var torchObserver: NSKeyValueObservation?
    /// Device identity used to discard stale torch notifications on main.
    private weak var observedDevice: AVCaptureDevice?
    /// Session notification tokens removed during disposal on main.
    private var observers: [NSObjectProtocol] = []
    /// Main-thread interface-orientation notification token.
    private var orientationObserver: NSObjectProtocol?
    /// Recognition endpoint storage protected by lifetimeLock.
    private var frameHandler: RecognitionHandler?
    /// Lock-protected access to the current recognition endpoint.
    private var handler: RecognitionHandler? {
        get {
            lifetimeLock.lock()
            defer {
                lifetimeLock.unlock()
            }
            return frameHandler
        }
        set {
            lifetimeLock.lock()
            frameHandler = newValue
            lifetimeLock.unlock()
        }
    }
    /// Receives changes from the active native camera.
    weak var cameraPreviewDelegate: CameraPreviewDelegate?
    /// Whether native session initialization has completed.
    private(set) var isInitialized = false
    /// Whether the selected camera currently reports its torch as active.
    var isTorchActive: Bool { sync { camera?.isTorchActive ?? false } }
    /// Recognition endpoint used for subsequently submitted camera frames.
    var recognitionHandler: RecognitionHandler? {
        get { handler }
        set {
            handler = newValue
            receiver?.setHandler(newValue)
        }
    }

    /// Binds the shared texture and observes interface orientation on main.
    init(output: CameraPreviewOutput) {
        self.output = output
        super.init()
        output.publish = { [weak self] description in
            guard let self = self else { return }
            self.cameraPreviewDelegate?.onPreviewChanged(self, description: description)
        }
        queue.setSpecific(key: queueKey, value: ())
        orientationObserver = NotificationCenter.default.addObserver(forName: UIDevice.orientationDidChangeNotification,
            object: nil, queue: .main) { [weak self] _ in self?.updateOrientation() }
    }

    /// Initializes the native camera session and reports completion on main.
    func initCamera(completion: @escaping (Error?) -> Void) {
        guard !closed else {
            completion(MlKitPluginError.cameraSessionDisposed)
            return
        }
        updateOrientation()
        queue.async { [weak self] in
            guard let self = self, !self.closed else {
                DispatchQueue.main.async {
                    completion(MlKitPluginError.cameraSessionDisposed)
                }
                return
            }
            do {
                let session = AVCaptureSession()
                session.sessionPreset = .hd1280x720
                guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
                    throw MlKitPluginError.initCameraError
                }
                let input = try AVCaptureDeviceInput(device: camera)
                guard session.canAddInput(input) else { throw MlKitPluginError.initCameraError }
                session.addInput(input)
                let video = AVCaptureVideoDataOutput()
                video.alwaysDiscardsLateVideoFrames = true
                video.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA)]
                guard session.canAddOutput(video) else { throw MlKitPluginError.initCameraError }
                session.addOutput(video)
                self.session = session
                self.camera = camera
                self.videoOutput = video
                self.installReceiver()
                DispatchQueue.main.async {
                    guard !self.closed else {
                        completion(MlKitPluginError.cameraSessionDisposed)
                        return
                    }
                    self.observeSession(session)
                    self.isInitialized = true
                    self.observeTorch(camera)
                    completion(nil)
                }
            } catch { DispatchQueue.main.async { completion(error) } }
        }
    }

    /// Selects the capture device while preserving the session on failure.
    func setCamera(_ data: CameraData) throws {
        let device = try sync {
            guard let session = session, !closed else { throw MlKitPluginError.cameraIsNotInitialized }
            guard let device = AVCaptureDevice.default(data.type, for: .video, position: data.position) else { throw MlKitPluginError.initCameraError }
            if camera?.uniqueID == device.uniqueID { return device }
            let input = try AVCaptureDeviceInput(device: device)
            let previous = session.inputs
            session.beginConfiguration()
            defer { session.commitConfiguration() }
            previous.forEach(session.removeInput)
            guard session.canAddInput(input) else {
                previous.filter(session.canAddInput).forEach(session.addInput)
                throw MlKitPluginError.initCameraError
            }
            session.addInput(input)
            camera = device
            installReceiver()
            return device
        }
        DispatchQueue.main.async { [weak self] in if self?.closed == false { self?.observeTorch(device) } }
    }

    /// Replaces the stream receiver on the session queue, revoking prior queued frames.
    private func installReceiver() {
        receiver?.close()
        let receiver = CameraFrameReceiver()
        self.receiver = receiver
        receiver.setRecognition(handler, viewport: viewport)
        receiver.onPreview = { [weak self, weak receiver] buffer in
            guard let self = self, let receiver = receiver, !self.closed,
                  self.receiver === receiver else { return }
            self.output.present(buffer)
            if let pending = self.pendingStart, pending.receiver === receiver {
                self.pendingStart = nil
                pending.finish(nil)
            }
        }
        videoOutput?.setSampleBufferDelegate(receiver, queue: analysisQueue)
        configureOrientation()
    }

    /// Starts the stream and completes when its first preview frame arrives.
    func resumeCamera(completion: @escaping (Error?) -> Void) {
        let request = CameraStart(completion)
        pendingStart?.finish(MlKitPluginError.cameraSessionDisposed)
        pendingStart = request
        queue.async { [weak self, weak request] in
            guard let self = self, let request = request else { return }
            guard request.active, !self.closed, let session = self.session else {
                DispatchQueue.main.async {
                    request.finish(MlKitPluginError.cameraIsNotInitialized)
                }
                return
            }
            self.installReceiver()
            request.receiver = self.receiver
            if !session.isRunning { session.startRunning() }
            if !session.isRunning { DispatchQueue.main.async { request.finish(MlKitPluginError.initCameraError) } }
        }
    }

    /// Cancels outstanding preparation and first-frame responses.
    func cancelPendingStart(completion: @escaping () -> Void) {
        let previous = preparation
        preparation = nil
        previous?.finish(MlKitPluginError.cameraSessionDisposed)
        let pending = pendingStart
        pendingStart = nil
        pending?.finish(MlKitPluginError.cameraSessionDisposed)
        completion()
    }

    /// Applies capture settings and viewport geometry before starting the stream.
    func prepare(_ settings: ScannerConfiguration, geometry: CGSize, completion: @escaping (Error?) -> Void) {
        let request = CameraPreparation(completion)
        preparation?.finish(MlKitPluginError.cameraSessionDisposed)
        preparation = request
        queue.async { [weak self] in
            guard let self = self, request.active, !self.closed else { return }
            var failure: Error?
            do {
                try self.setCamera(settings.camera)
                self.updateGeometry(geometry)
                self.setCropArea(settings.cropRect)
                self.resetFocus()
                try self.apply(.zoom) { try self.setZoomRatio(settings.zoomRatio) }
                try self.apply(.torch) { try self.setFlash(settings.torchEnabled) }
            } catch { failure = error }
            DispatchQueue.main.async {
                guard request.active, !self.closed, self.preparation === request else { return }
                self.preparation = nil
                request.finish(failure)
            }
        }
    }

    /// Adds operation context while preserving the unsupported-flash error contract.
    private func apply(_ operation: CameraControlOperation, action: () throws -> Void) throws {
        do { try action() }
        catch MlKitPluginError.deviceHasNotFlash { throw MlKitPluginError.deviceHasNotFlash }
        catch { throw CameraControlError(operation: operation, underlyingError: error) }
    }

    /// Stops capture while retaining the most recent texture frame.
    func pauseCamera(completion: @escaping () -> Void) {
        cancelPendingStart {}
        receiver?.close()
        queue.async { [weak self] in
            self?.receiver?.close()
            if let session = self?.session, session.isRunning { session.stopRunning() }
            DispatchQueue.main.async {
                self?.output.pause()
                completion()
            }
        }
    }

    /// Applies the requested torch state or reports unsupported hardware.
    func setFlash(_ enabled: Bool) throws {
        try withCamera { camera in
            guard camera.hasTorch else {
                if enabled {
                    throw MlKitPluginError.deviceHasNotFlash
                }
                return
            }
            let mode: AVCaptureDevice.TorchMode = enabled ? .on : .off
            guard camera.isTorchModeSupported(mode) else { throw MlKitPluginError.deviceHasNotFlash }
            camera.torchMode = mode
        }
    }

    /// Applies an absolute zoom factor to the selected camera.
    func setZoomRatio(_ value: Double) throws {
        try withCamera { camera in
            let zoom = CGFloat(value)
            guard zoom.isFinite, zoom >= camera.minAvailableVideoZoomFactor, zoom <= camera.maxAvailableVideoZoomFactor else { throw MlKitPluginError.invalidArguments }
            camera.videoZoomFactor = zoom
        }
    }

    /// Best-effort restoration of continuous focus before capture.
    func resetFocus() {
        try? focus(locked: false)
    }

    /// Applies autofocus and exposure modes at the recognition-area center.
    func focus(locked: Bool) throws {
        try withCamera { camera in
            let dimensions = receiver?.latestSize ?? CGSize(width: 720, height: 1280)
            let geometry = CameraFrameGeometry(source: dimensions, viewport: viewport)
            var point = geometry.normalizedPoint(CGPoint(x: (1 + crop.offsetX) / 2, y: (1 + crop.offsetY) / 2))
            if camera.position == .front { point.x = 1 - point.x }
            switch orientation {
            case .portrait: point = CGPoint(x: point.y, y: 1 - point.x)
            case .portraitUpsideDown: point = CGPoint(x: 1 - point.y, y: point.x)
            case .landscapeLeft: point = CGPoint(x: 1 - point.x, y: 1 - point.y)
            default: break
            }
            if camera.isFocusPointOfInterestSupported { camera.focusPointOfInterest = point }
            if camera.isExposurePointOfInterestSupported { camera.exposurePointOfInterest = point }
            let focus: AVCaptureDevice.FocusMode = locked ? .autoFocus : .continuousAutoFocus
            let exposure: AVCaptureDevice.ExposureMode = locked ? .autoExpose : .continuousAutoExposure
            if camera.isFocusModeSupported(focus) { camera.focusMode = focus }
            if camera.isExposureModeSupported(exposure) { camera.exposureMode = exposure }
        }
    }

    /// Updates normalized focus geometry while refreshing the frame endpoint.
    func setCropArea(_ cropRect: CropRect) {
        sync {
            crop = cropRect
            receiver?.setRecognition(handler, viewport: viewport)
        }
    }

    /// Updates the viewport used to map preview coordinates into camera frames.
    func updateGeometry(_ size: CGSize) {
        sync {
            viewport = size
            receiver?.setRecognition(handler, viewport: viewport)
        }
    }

    /// Runs a camera control on the session queue with its configuration lock held.
    private func withCamera(_ action: (AVCaptureDevice) throws -> Void) throws {
        try sync {
            guard !closed, let camera = camera else { throw MlKitPluginError.cameraIsNotInitialized }
            try camera.lockForConfiguration()
            defer { camera.unlockForConfiguration() }
            try action(camera)
        }
    }

    /// Reads interface orientation on main and schedules its session update.
    private func updateOrientation() {
        let value: UIInterfaceOrientation
        if #available(iOS 13.0, *) {
            value = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.interfaceOrientation }.first ?? .portrait
        } else { value = UIApplication.shared.statusBarOrientation }
        let next: AVCaptureVideoOrientation
        switch value {
        case .landscapeLeft: next = .landscapeLeft
        case .landscapeRight: next = .landscapeRight
        case .portraitUpsideDown: next = .portraitUpsideDown
        default: next = .portrait
        }
        queue.async { [weak self] in
            self?.orientation = next
            self?.configureOrientation()
        }
    }

    /// Applies orientation and front-camera mirroring to the video connection.
    private func configureOrientation() {
        guard let connection = videoOutput?.connection(with: .video) else { return }
        if connection.isVideoOrientationSupported { connection.videoOrientation = orientation }
        if connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = camera?.position == .front
        }
    }

    /// Observes the selected device and filters stale notifications by identity.
    private func observeTorch(_ device: AVCaptureDevice) {
        torchObserver?.invalidate()
        observedDevice = device
        torchObserver = device.observe(\.isTorchActive, options: .new) { [weak self] device, _ in
            DispatchQueue.main.async {
                guard let self = self, !self.closed, self.observedDevice === device else { return }
                self.cameraPreviewDelegate?.onTorchChanged(self, enabled: device.isTorchActive)
            }
        }
    }

    /// Pauses preview delivery when the native session fails or is interrupted.
    private func observeSession(_ session: AVCaptureSession) {
        for name in [NSNotification.Name.AVCaptureSessionRuntimeError, .AVCaptureSessionWasInterrupted] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: session, queue: .main) { [weak self] _ in
                guard let self = self, !self.closed else { return }
                self.cancelPendingStart {}
                self.output.pause()
            })
        }
    }

    /// Releases native session resources and completes after texture disposal.
    func dispose(completion: @escaping () -> Void) {
        guard !closed else {
            completion()
            return
        }
        closed = true
        receiver?.close()
        cancelPendingStart {}
        torchObserver?.invalidate()
        torchObserver = nil
        if let observer = orientationObserver { NotificationCenter.default.removeObserver(observer) }
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        queue.async {
            self.receiver?.close()
            self.receiver = nil
            self.videoOutput?.setSampleBufferDelegate(nil, queue: nil)
            if let session = self.session, session.isRunning { session.stopRunning() }
            self.session = nil
            self.camera = nil
            self.videoOutput = nil
            DispatchQueue.main.async {
                self.output.dispose()
                completion()
            }
        }
    }

    /// Executes on the session queue without deadlocking when already on that queue.
    private func sync<T>(_ action: () throws -> T) rethrows -> T {
        if DispatchQueue.getSpecific(key: queueKey) != nil { return try action() }
        return try queue.sync(execute: action)
    }
}

/// A cancellable configuration request admitted to the serial session queue.
private final class CameraPreparation {
    /// Protects mutable state shared across callback queues.
    private let lock = NSLock()
    /// Pending response, cleared before delivery to enforce one completion.
    private var completion: ((Error?) -> Void)?

    /// Stores one cancellable camera-configuration response.
    init(_ completion: @escaping (Error?) -> Void) {
        self.completion = completion
    }
    /// Whether the request still has an unfinished response.
    var active: Bool {
        lock.lock()
        defer {
            lock.unlock()
        }
        return completion != nil
    }

    /// Takes the pending response under the lock, then invokes it after unlocking.
    func finish(_ error: Error?) {
        lock.lock()
        let callback = completion
        completion = nil
        lock.unlock()
        callback?(error)
    }
}

/// Owns one unfinished start response; cancellation and first frame can complete it only once.
private final class CameraStart {
    /// Protects mutable state shared across callback queues.
    private let lock = NSLock()
    /// Pending response, cleared before delivery to enforce one completion.
    private var completion: ((Error?) -> Void)?
    /// Receiver identity associated with this start; protected by lock.
    private var output: CameraFrameReceiver?
    /// Lock-protected identity of the stream that can complete this start.
    var receiver: CameraFrameReceiver? {
        get {
            lock.lock()
            defer {
                lock.unlock()
            }
            return output
        }
        set {
            lock.lock()
            output = newValue
            lock.unlock()
        }
    }
    /// Whether the request still has an unfinished response.
    var active: Bool {
        lock.lock()
        defer {
            lock.unlock()
        }
        return completion != nil
    }

    /// Stores one response awaiting the first frame of its stream.
    init(_ completion: @escaping (Error?) -> Void) {
        self.completion = completion
    }

    /// Takes the pending response under the lock, then invokes it after unlocking.
    func finish(_ error: Error?) {
        lock.lock()
        let callback = completion
        completion = nil
        lock.unlock()
        callback?(error)
    }
}
