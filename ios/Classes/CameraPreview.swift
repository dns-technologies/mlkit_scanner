//
//  CameraPreview.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 04.03.2021.
//

import AVFoundation
import Flutter
import UIKit

/// Receives view-scoped camera preview events.
protocol CameraPreviewDelegate: AnyObject {
    /// Reports a native torch-state change for one platform view.
    func onToggleTorch(value: Bool, viewId: Int64)
    /// Returns whether a focus gesture still belongs to the active camera owner.
    func canApplyFocus(viewId: Int64) -> Bool
}

/// Native iOS camera preview owned by one Flutter platform view.
class CameraPreview: NSObject, FlutterPlatformView, CameraPreviewing {
    /// Flutter identifier of the platform view that owns this preview.
    let viewId: Int64
    let registrationToken: UUID
    private let preview: UIContainer
    private var scaleX, scaleY: CGFloat
    private var offsetX, offsetY: CGFloat
    private var focusPoint: CGPoint
    private var captureSession: AVCaptureSession?
    private var camera: AVCaptureDevice?
    private var videoOutput: AVCaptureVideoDataOutput?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private let frameStateLock = NSLock()
    private let lifecycleLock = NSLock()
    /// Serializes physical camera-session work across every Flutter preview.
    ///
    /// A single queue prevents one route from starting capture before the
    /// previous route has finished releasing the physical camera.
    private static let sessionQueueKey = DispatchSpecificKey<Void>()
    private static let sessionQueue: DispatchQueue = {
        let queue = DispatchQueue(
            label: "mlkit_scanner.camera_session",
            qos: .userInitiated
        )
        queue.setSpecific(key: sessionQueueKey, value: ())
        return queue
    }()
    private let videoOutputQueue: DispatchQueue
    private var torchObserver: NSKeyValueObservation?
    private var captureSessionObservers: [NSObjectProtocol] = []
    private var layoutReadyCompletions: [() -> Void] = []
    private var streamingCompletion: ((Error?) -> Void)?
    private var isStreaming = false
    private var scannerOverlay: ScannerOverlay?
    private let onDispose: (Int64, UUID) -> Void
    private var disposed = false
    
    private let focusView: FocusView
    private weak var currentRecognitionHandler: RecognitionHandler?
    var recognitionHandler: RecognitionHandler? {
        get {
            frameStateLock.lock()
            defer { frameStateLock.unlock() }
            return currentRecognitionHandler
        }
        set {
            frameStateLock.lock()
            currentRecognitionHandler = newValue
            frameStateLock.unlock()
        }
    }
    weak var cameraPreviewDelegate: CameraPreviewDelegate?
    
    /// Creates a native preview without starting camera capture.
    init(
        frame: CGRect,
        viewId: Int64,
        registrationToken: UUID = UUID(),
        offsetX: CGFloat = 0,
        offsetY: CGFloat = 0,
        onDispose: @escaping (Int64, UUID) -> Void = { _, _ in }
    ) {
        self.viewId = viewId
        self.registrationToken = registrationToken
        self.onDispose = onDispose
        videoOutputQueue = DispatchQueue(
            label: "mlkit_scanner.video_output.\(viewId)",
            qos: .userInitiated
        )
        preview = UIContainer(frame: frame)
        (scaleX, scaleY) = CameraPreview.previewScale(for: frame)
        (self.offsetX, self.offsetY) = (offsetX, offsetY)
        focusPoint = PreviewGeometry.normalizedFocusPoint(offsetX: offsetX, offsetY: offsetY)
        focusView = FocusView(
            frame: preview.bounds,
            point: PreviewGeometry.focusPosition(
                in: preview.bounds,
                normalizedPoint: focusPoint
            )
        )
        super.init()
        preview.delegate = self
        focusView.delegate = self
    }

    deinit {
        dispose()
        onDispose(viewId, registrationToken)
    }

    /// Whether UIKit has supplied finite, nonempty preview bounds.
    var isLayoutReady: Bool {
        PreviewGeometry.isLayoutReady(preview.bounds)
    }

    /// Whether resource teardown has already started.
    private var isDisposed: Bool {
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        return disposed
    }

    /// Calls `completion` after the preview first receives usable bounds.
    func whenLayoutReady(_ completion: @escaping () -> Void) {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in
                self?.whenLayoutReady(completion)
            }
            return
        }
        guard !isDisposed else { return }
        if isLayoutReady {
            completion()
        } else {
            layoutReadyCompletions.append(completion)
        }
    }

    /// Clears camera and overlay focus-lock state.
    private func clearFocusLock() {
        focusOnCenter(needLock: false)
        DispatchQueue.main.async { [weak self] in
            self?.focusView.cancelLockFocus()
        }
    }
    
    /// Returns the native view hosted by Flutter.
    func view() -> UIView {
        return preview
    }
    
    /// Requests permission and prepares a capture session without starting it.
    ///
    /// Camera work runs off the main thread. `completion` receives an error when
    /// authorization or session initialization fails. View-owned configuration
    /// is applied later only while this preview is the active camera owner.
    func initCamera(
        completion: @escaping (Error?) -> ()
    ) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureCamera(completion: completion)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self = self else {
                        completion(MlKitPluginError.cameraIsNotInitialized)
                        return
                    }
                    guard granted else {
                        completion(MlKitPluginError.authorizationCameraError)
                        return
                    }
                    self.configureCamera(completion: completion)
                }
            }
        default:
            completion(MlKitPluginError.authorizationCameraError)
        }
    }

    /// Builds a camera session without acquiring the camera for frame capture.
    private func configureCamera(
        completion: @escaping (Error?) -> ()
    ) {
        do {
            camera = createWideAngleCamera()
            guard let camera = camera else {
                completion(MlKitPluginError.initCameraError)
                return
            }

            let input = try AVCaptureDeviceInput.init(device: camera)
            captureSession = AVCaptureSession()
            captureSession?.sessionPreset = .hd1280x720
            guard captureSession?.canAddInput(input) == true else {
                completion(MlKitPluginError.initCameraError)
                return
            }
            captureSession?.addInput(input)
        } catch {
            completion(error)
            return
        }

        guard let captureSession = captureSession else {
            completion(MlKitPluginError.cameraIsNotInitialized)
            return
        }
        let previewLayer = AVCaptureVideoPreviewLayer(session: captureSession)
        self.previewLayer = previewLayer
        previewLayer.videoGravity = .resizeAspectFill
        updateVideoOrientation()
        previewLayer.frame = preview.bounds
        preview.layer.insertSublayer(previewLayer, at: 0)
        addFocusView()

        subscribeOrientationChanges()
        observeCaptureSession(captureSession)
        observeTorchToggle()
        CameraPreview.sessionQueue.async { [weak self] in
            guard let self = self, let session = self.captureSession else {
                completion(MlKitPluginError.cameraIsNotInitialized)
                return
            }
            let videoOutput = AVCaptureVideoDataOutput()
            videoOutput.alwaysDiscardsLateVideoFrames = true
            videoOutput.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String:
                    Int(kCVPixelFormatType_32BGRA),
            ]
            videoOutput.setSampleBufferDelegate(
                self,
                queue: self.videoOutputQueue
            )
            guard session.canAddOutput(videoOutput) else {
                completion(MlKitPluginError.initCameraError)
                return
            }
            self.videoOutput = videoOutput
            session.addOutput(videoOutput)
            completion(nil)
        }
    }

    /// Replaces the active capture device while preserving the session output.
    ///
    /// Throws when capture is not initialized or the requested camera cannot be
    /// attached.
    func setCamera(_ cameraData: CameraData) throws {
        guard let session = self.captureSession else {
            throw MlKitPluginError.cameraIsNotInitialized
        }

        guard let newCamera = AVCaptureDevice.default(cameraData.type, for: .video, position: cameraData.position) else {
            throw MlKitPluginError.initCameraError
        }

        let newInput = try AVCaptureDeviceInput.init(device: newCamera)

        try CameraPreview.syncOnSessionQueue {
            let currentInputs = session.inputs
            session.beginConfiguration()
            defer { session.commitConfiguration() }

            currentInputs.forEach { session.removeInput($0) }
            guard session.canAddInput(newInput) else {
                currentInputs
                    .filter { session.canAddInput($0) }
                    .forEach { session.addInput($0) }
                throw MlKitPluginError.initCameraError
            }
            session.addInput(newInput)
        }

        camera = newCamera

        torchObserver?.invalidate()
        observeTorchToggle()
    }    

    /// Returns the default back wide-angle camera.
    private func createWideAngleCamera() -> AVCaptureDevice? {
        return AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
    }

    /// Places the focus gesture overlay above the native preview layer.
    private func addFocusView() {
        preview.addSubview(focusView)
    }

    /// Updates the camera and overlay focus point from normalized center offsets.
    func changeFocusCenter(offsetX: CGFloat, offsetY: CGFloat) {
        (self.offsetX, self.offsetY) = (offsetX, offsetY)
        focusPoint = PreviewGeometry.normalizedFocusPoint(offsetX: offsetX, offsetY: offsetY)
        focusView.moveFocus(
            to: PreviewGeometry.focusPosition(
                in: preview.bounds,
                normalizedPoint: focusPoint
            )
        )
    }

    /// Applies an explicit retained torch state.
    func setFlash(_ enabled: Bool) throws {
        guard
            captureSession != nil,
            let camera = camera,
            camera.isConnected
        else {
            throw MlKitPluginError.cameraIsNotInitialized
        }
        guard camera.hasTorch else {
            if !enabled { return }
            throw MlKitPluginError.deviceHasNotFlash
        }
        let requestedMode: AVCaptureDevice.TorchMode = enabled ? .on : .off
        guard camera.isTorchModeSupported(requestedMode) else {
            if !enabled { return }
            throw MlKitPluginError.deviceHasNotFlash
        }
        try camera.lockForConfiguration()
        defer { camera.unlockForConfiguration() }
        camera.torchMode = requestedMode
    }

    /// Clears focus state retained by a previous camera owner.
    func resetFocus() {
        clearFocusLock()
    }

    /// Updates the recognition rectangle and its focus center.
    func setCropArea(_ cropRect: CropRect) {
        changeFocusCenter(offsetX: cropRect.offsetX, offsetY: cropRect.offsetY)
        if let scannerOverlay = scannerOverlay {
            scannerOverlay.updateCropRect(rect: cropRect)
        } else {
            let scannerOverlay = ScannerOverlay(cropRect: cropRect)
            self.scannerOverlay = scannerOverlay
            preview.insertSubview(scannerOverlay, belowSubview: focusView)
        }
    }

    /// Updates whether the scanner overlay indicates active recognition.
    func setScanActive(_ isActive: Bool) {
        scannerOverlay?.isActive = isActive
    }

    /// Stops the capture session asynchronously without releasing its resources.
    func pauseCamera(completion: @escaping () -> ()) {
        CameraPreview.sessionQueue.async { [weak self] in
            guard let self = self else {
                completion()
                return
            }
            let pendingStreamingCompletion = self.streamingCompletion
            self.streamingCompletion = nil
            self.isStreaming = false
            if let session = self.captureSession, session.isRunning {
                session.stopRunning()
            }
            pendingStreamingCompletion?(nil)
            completion()
        }
    }

    /// Restarts the capture session asynchronously.
    ///
    /// `completion` receives an error when the camera is not initialized.
    func resumeCamera(completion: @escaping (Error?) -> ()) {
        CameraPreview.sessionQueue.async { [weak self] in
            guard let self = self,
                  !self.isDisposed,
                  let session = self.captureSession,
                  let camera = self.camera,
                  camera.isConnected else {
                completion(MlKitPluginError.cameraIsNotInitialized)
                return
            }
            if session.isRunning, self.isStreaming {
                completion(nil)
                return
            }
            self.streamingCompletion = completion
            self.isStreaming = false
            if !session.isRunning {
                session.startRunning()
            }
            if !session.isRunning {
                let pendingCompletion = self.streamingCompletion
                self.streamingCompletion = nil
                pendingCompletion?(MlKitPluginError.initCameraError)
            }
        }
    }

    /// Idempotently releases capture, observation, and preview resources.
    func dispose() {
        lifecycleLock.lock()
        guard !disposed else {
            lifecycleLock.unlock()
            return
        }
        disposed = true
        lifecycleLock.unlock()
        layoutReadyCompletions.removeAll()
        torchObserver?.invalidate()
        torchObserver = nil
        captureSessionObservers.forEach(NotificationCenter.default.removeObserver)
        captureSessionObservers.removeAll()
        NotificationCenter.default.removeObserver(self)
        recognitionHandler = nil
        cameraPreviewDelegate = nil
        scannerOverlay?.removeFromSuperview()
        scannerOverlay = nil
        previewLayer?.removeFromSuperlayer()
        previewLayer = nil
        let resources = CameraPreview.syncOnSessionQueue { () -> (
            AVCaptureSession?,
            AVCaptureVideoDataOutput?,
            ((Error?) -> Void)?
        ) in
            let resources = (captureSession, videoOutput, streamingCompletion)
            streamingCompletion = nil
            isStreaming = false
            captureSession = nil
            camera = nil
            videoOutput = nil
            return resources
        }
        resources.1?.setSampleBufferDelegate(nil, queue: nil)
        CameraPreview.sessionQueue.async {
            if let session = resources.0, session.isRunning {
                session.stopRunning()
            }
            resources.2?(MlKitPluginError.cameraSessionDisposed)
        }
    }

    /// Subscribes to interface-orientation changes affecting preview output.
    private func subscribeOrientationChanges() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(onOrientationChanges),
            name: UIDevice.orientationDidChangeNotification,
            object: nil
        )
    }

    /// Updates the preview connection after interface orientation changes.
    @objc private func onOrientationChanges() {
        updateVideoOrientation()
    }

    /// Applies the current interface orientation when the preview connection supports it.
    private func updateVideoOrientation() {
        guard let connection = previewLayer?.connection,
              connection.isVideoOrientationSupported else {
            return
        }
        connection.videoOrientation = getVideoOrientation()
    }

    /// Maps the current interface orientation to camera output orientation.
    private func getVideoOrientation() -> AVCaptureVideoOrientation {
        let interfaceOrientation: UIInterfaceOrientation
        if #available(iOS 13.0, *),
           let windowOrientation = preview.window?.windowScene?.interfaceOrientation {
            interfaceOrientation = windowOrientation
        } else {
            interfaceOrientation = UIApplication.shared.statusBarOrientation
        }
        switch interfaceOrientation {
        case .landscapeRight:
            return .landscapeRight
        case .landscapeLeft:
            return .landscapeLeft
        case .portrait:
            return .portrait
        case .portraitUpsideDown:
            return .portraitUpsideDown
        default:
            return .portrait
        }
    }

    /// Applies an absolute zoom ratio supported by the selected capture device.
    func setZoomRatio(_ value: Double) throws {
        guard let camera = camera else {
            throw MlKitPluginError.cameraIsNotInitialized
        }
        let zoomRatio = CGFloat(value)
        guard
            zoomRatio.isFinite,
            zoomRatio >= camera.minAvailableVideoZoomFactor,
            zoomRatio <= camera.maxAvailableVideoZoomFactor
        else {
            throw MlKitPluginError.invalidArguments
        }
        try camera.lockForConfiguration()
        defer { camera.unlockForConfiguration() }
        camera.videoZoomFactor = zoomRatio
    }
    
    /// Observes hardware torch activity and reports it with this view's identity.
    private func observeTorchToggle() {
        torchObserver = camera?.observe(\.isTorchActive, options: .new) { [weak self] _, observable in
            guard let isActive = observable.newValue else { return }
            guard let self = self else { return }
            self.cameraPreviewDelegate?.onToggleTorch(value: isActive, viewId: self.viewId)
        }
    }

    /// Observes unexpected capture stops so a pending activation never hangs.
    private func observeCaptureSession(_ session: AVCaptureSession) {
        let runtimeErrorObserver = NotificationCenter.default.addObserver(
            forName: .AVCaptureSessionRuntimeError,
            object: session,
            queue: nil
        ) { [weak self] notification in
            let error = notification.userInfo?[AVCaptureSessionErrorKey] as? Error
                ?? MlKitPluginError.initCameraError
            CameraPreview.sessionQueue.async {
                self?.finishPendingStreaming(error: error)
            }
        }
        let interruptedObserver = NotificationCenter.default.addObserver(
            forName: .AVCaptureSessionWasInterrupted,
            object: session,
            queue: nil
        ) { [weak self] _ in
            CameraPreview.sessionQueue.async {
                self?.isStreaming = false
            }
        }
        captureSessionObservers = [runtimeErrorObserver, interruptedObserver]
    }

    /// Completes the active start request after the first frame or an error.
    private func finishPendingStreaming(error: Error?) {
        if error == nil {
            guard captureSession?.isRunning == true else { return }
            isStreaming = true
        } else {
            isStreaming = false
        }
        let completion = streamingCompletion
        streamingCompletion = nil
        completion?(error)
    }

    /// Returns preview-to-screen scale without propagating invalid geometry.
    private static func previewScale(for bounds: CGRect) -> (CGFloat, CGFloat) {
        let screenBounds = UIScreen.main.bounds
        guard bounds.isFinite,
              screenBounds.width > 0,
              screenBounds.height > 0 else {
            return (0, 0)
        }
        return (
            bounds.width / screenBounds.width,
            bounds.height / screenBounds.height
        )
    }

    /// Executes one short state transaction on the shared capture-session queue.
    private static func syncOnSessionQueue<T>(_ operation: () throws -> T) rethrows -> T {
        if DispatchQueue.getSpecific(key: sessionQueueKey) != nil {
            return try operation()
        }
        return try sessionQueue.sync(execute: operation)
    }
}

extension CameraPreview: AVCaptureVideoDataOutputSampleBufferDelegate {
    /// Forwards camera frames to the currently active recognition handler.
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        CameraPreview.sessionQueue.async { [weak self] in
            self?.finishPendingStreaming(error: nil)
        }
        frameStateLock.lock()
        let handler = currentRecognitionHandler
        let currentScaleX = scaleX
        let currentScaleY = scaleY
        frameStateLock.unlock()
        handler?.processVideoOutput(
            sampleBuffer: sampleBuffer,
            scaleX: currentScaleX,
            scaleY: currentScaleY,
            orientation: connection.videoOrientation
        )
    }
}

extension CameraPreview: FocusViewDelegate {
    /// Requests continuous focus at the current overlay center.
    func onFocus() {
        guard cameraPreviewDelegate?.canApplyFocus(viewId: viewId) == true else { return }
        focusOnCenter(needLock: false)
    }
    
    /// Requests a one-shot focus lock at the current overlay center.
    func onLockFocus() {
        guard cameraPreviewDelegate?.canApplyFocus(viewId: viewId) == true else { return }
        focusOnCenter(needLock: true)
    }
    
    /// Applies continuous or locked focus and exposure at the current focus point.
    private func focusOnCenter(needLock: Bool) {
        guard let camera = camera else {
            return
        }
        do {
            try camera.lockForConfiguration()
            defer { camera.unlockForConfiguration() }
            if camera.isFocusPointOfInterestSupported {
                camera.focusPointOfInterest = focusPoint
            }
            if camera.isExposurePointOfInterestSupported {
                camera.exposurePointOfInterest = focusPoint
            }
            if (needLock) {
                if camera.isExposureModeSupported(.autoExpose) {
                    camera.exposureMode = .autoExpose
                }
                if camera.isFocusModeSupported(.autoFocus) {
                    camera.focusMode = .autoFocus
                }
            } else {
                if camera.isExposureModeSupported(.continuousAutoExposure) {
                    camera.exposureMode = .continuousAutoExposure
                }
                if camera.isFocusModeSupported(.continuousAutoFocus) {
                    camera.focusMode = .continuousAutoFocus
                }
            }
        } catch {}
    }
}

extension CameraPreview: UIContainerDelegate {
    /// Recalculates preview geometry and focus coordinates during layout.
    func viewWillLayoutSubviews() {
        let previewScale = CameraPreview.previewScale(for: preview.bounds)
        frameStateLock.lock()
        (scaleX, scaleY) = previewScale
        frameStateLock.unlock()
        previewLayer?.frame = preview.bounds
        updateVideoOrientation()
        focusPoint = PreviewGeometry.normalizedFocusPoint(offsetX: offsetX, offsetY: offsetY)
        focusView.moveFocus(
            to: PreviewGeometry.focusPosition(
                in: preview.bounds,
                normalizedPoint: focusPoint
            )
        )
        guard isLayoutReady, !layoutReadyCompletions.isEmpty else { return }
        let completions = layoutReadyCompletions
        layoutReadyCompletions.removeAll()
        completions.forEach { $0() }
    }
}

fileprivate protocol UIContainerDelegate: AnyObject {
    /// Called to notify the UIContainerDelegate that view is about to layout its subviews.
    func viewWillLayoutSubviews()
}

/// Empty container. Depends on height and width constraints.
fileprivate class UIContainer : UIView {
    weak var delegate: UIContainerDelegate?
    
    /// Creates a preview container with the supplied frame.
    override init(frame: CGRect) {
        super.init(frame: frame)
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
    
    /// Notifies the delegate before dependent overlays are repositioned.
    override func layoutSubviews() {
        super.layoutSubviews()
        delegate?.viewWillLayoutSubviews()
    }
    
}

private extension CGRect {
    var isFinite: Bool {
        origin.x.isFinite
            && origin.y.isFinite
            && size.width.isFinite
            && size.height.isFinite
    }
}
