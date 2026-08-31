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
}

/// Native iOS camera preview owned by one Flutter platform view.
class CameraPreview: NSObject, FlutterPlatformView {
    /// Flutter identifier of the platform view that owns this preview.
    let viewId: Int64
    private let preview: UIContainer
    private var scaleX, scaleY: CGFloat
    private var offsetX, offsetY: CGFloat
    private var focusPoint: CGPoint
    private var captureSession: AVCaptureSession?
    private var camera: AVCaptureDevice?
    private var videoOutput: AVCaptureVideoDataOutput?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private let sessionQueue = DispatchQueue(
        label: "mlkit_scanner.camera_session",
        qos: .userInitiated
    )
    private var torchObserver: NSKeyValueObservation?
    private let onDispose: (Int64) -> Void
    private var isDisposed = false
    
    private let focusView: FocusView
    weak var recognitionHandler: RecognitionHandler?
    weak var cameraPreviewDelegate: CameraPreviewDelegate?
    
    /// Whether the selected camera exposes a torch.
    var hasFlash: Bool {
        return camera?.hasTorch == true
    }

    /// Creates a native preview without starting camera capture.
    init(
        frame: CGRect,
        viewId: Int64,
        offsetX: CGFloat = 0,
        offsetY: CGFloat = 0,
        onDispose: @escaping (Int64) -> Void = { _ in }
    ) {
        self.viewId = viewId
        self.onDispose = onDispose
        preview = UIContainer(frame: frame)
        (scaleX, scaleY) = (frame.width / UIScreen.main.bounds.width, frame.height / UIScreen.main.bounds.height)
        (self.offsetX, self.offsetY) = (offsetX, offsetY)
        let focusPoint = CameraPreview.calcFocusPoint(preview: preview, offsetX: offsetX, offsetY: offsetY)
        self.focusPoint = focusPoint.normalized()
        focusView = FocusView(frame: preview.frame, point: focusPoint.position())
        super.init()
        preview.delegate = self
        focusView.delegate = self
    }

    deinit {
        dispose()
        onDispose(viewId)
    }
    
    /// Subscribes to session startup so focus lock can be reset after resume.
    private func subscribeCaptureSessionStopNotification() {
        guard let captureSession = captureSession else { return }
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(self.onCaptureSessionStart),
            name: .AVCaptureSessionDidStartRunning,
            object: captureSession)
    }
    
    /// Restores continuous focus after the capture session starts.
    @objc private func onCaptureSessionStart() {
        clearFocusLock()
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
    
    /// Requests permission, configures the selected camera, and starts capture.
    ///
    /// Camera work runs off the main thread. `completion` receives an error when
    /// authorization, device selection, or session configuration fails.
    func initCamera(
        initialZoom: Double?,
        initialCamera: CameraData?,
        shouldStart: @escaping () -> Bool,
        completion: @escaping (Error?) -> ()
    ) {
        guard shouldStart() else {
            completion(MlKitPluginError.cameraIsNotInitialized)
            return
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureCamera(
                initialZoom: initialZoom,
                initialCamera: initialCamera,
                completion: completion
            )
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
                    guard shouldStart() else {
                        completion(MlKitPluginError.cameraIsNotInitialized)
                        return
                    }
                    self.configureCamera(
                        initialZoom: initialZoom,
                        initialCamera: initialCamera,
                        completion: completion
                    )
                }
            }
        default:
            completion(MlKitPluginError.authorizationCameraError)
        }
    }

    /// Builds and starts an AVFoundation session for the requested initial state.
    private func configureCamera(
        initialZoom: Double?,
        initialCamera: CameraData?,
        completion: @escaping (Error?) -> ()
    ) {
        do {
            if let initialCamera = initialCamera {
                camera = AVCaptureDevice.default(
                    initialCamera.type,
                    for: .video,
                    position: initialCamera.position
                )
            } else {
                camera = createWideAngleCamera()
            }
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
            subscribeCaptureSessionStopNotification()

            if let initialZoom = initialZoom {
                try setZoom(initialZoom)
            }
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
        previewLayer.connection?.videoOrientation = getVideoOrieitation()
        previewLayer.frame = preview.frame
        preview.layer.insertSublayer(previewLayer, at: 0)
        addFocusView()

        subscribeOrientationChanges()
        self.observeTorchToggle()
        sessionQueue.async {  [weak self] in
            guard let self = self, let session = self.captureSession else {
                completion(MlKitPluginError.cameraIsNotInitialized)
                return
            }
            let videoOutput = AVCaptureVideoDataOutput()
            videoOutput.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String:
                    Int(kCVPixelFormatType_32BGRA),
            ]
            videoOutput.setSampleBufferDelegate(
                self,
                queue: .global(qos: .userInitiated)
            )
            guard session.canAddOutput(videoOutput) else {
                completion(MlKitPluginError.initCameraError)
                return
            }
            self.videoOutput = videoOutput
            session.addOutput(videoOutput)
            session.startRunning()
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

        try sessionQueue.sync {
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

        clearFocusLock()
    }    

    /// Returns the default back wide-angle camera.
    private func createWideAngleCamera() -> AVCaptureDevice? {
        return AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
    }

    /// Places the focus gesture overlay above the native preview layer.
    private func addFocusView() {
        preview.addSubview(focusView)
    }

    /// Calculates a focus point from normalized preview-center offsets.
    private class func calcFocusPoint(preview: UIContainer, offsetX: CGFloat, offsetY: CGFloat) -> FocusPoint {
        return FocusPoint(frame: preview.frame, offsetX: offsetX, offsetY: offsetY)
    }

    /// Updates the camera and overlay focus point from normalized center offsets.
    func changeFocusCenter(offsetX: CGFloat, offsetY: CGFloat) {
        (self.offsetX, self.offsetY) = (offsetX, offsetY)
        let focusPoint = CameraPreview.calcFocusPoint(preview: preview, offsetX: offsetX, offsetY: offsetY)
        self.focusPoint = focusPoint.normalized()
        focusView.changeFocusPoint(point: focusPoint.position())
    }

    /// Applies an explicit retained torch state.
    func setFlash(_ enabled: Bool) throws {
        guard
            let session = captureSession,
            session.isRunning,
            let camera = camera,
            camera.isConnected
        else {
            throw MlKitPluginError.cameraIsNotInitialized
        }
        guard camera.hasTorch else {
            if !enabled { return }
            throw MlKitPluginError.deviceHasNotFlash
        }
        try camera.lockForConfiguration()
        camera.torchMode = enabled ? .on : .off
        camera.unlockForConfiguration()
    }

    /// Stops the capture session asynchronously without releasing its resources.
    func pauseCamera(completion: @escaping () -> ()) {
        sessionQueue.async { [weak self] in
            guard let self = self else {
                completion()
                return
            }
            if let session = self.captureSession, session.isRunning {
                session.stopRunning()
            }
            completion()
        }
    }

    /// Restarts the capture session asynchronously.
    ///
    /// `completion` receives an error when the camera is not initialized.
    func resumeCamera(completion: @escaping (Error?) -> ()) {
        sessionQueue.async { [weak self] in
            guard let session = self?.captureSession, let camera = self?.camera, camera.isConnected else {
                completion(MlKitPluginError.cameraIsNotInitialized)
                return
            }
            if (!session.isRunning) {
                session.startRunning()
            }
            completion(nil)
        }
    }

    /// Idempotently releases capture, observation, and preview resources.
    func dispose() {
        guard !isDisposed else { return }
        isDisposed = true
        torchObserver?.invalidate()
        torchObserver = nil
        NotificationCenter.default.removeObserver(self)
        recognitionHandler = nil
        cameraPreviewDelegate = nil
        previewLayer?.removeFromSuperlayer()
        previewLayer = nil
        videoOutput?.setSampleBufferDelegate(nil, queue: nil)
        let session = captureSession
        captureSession = nil
        camera = nil
        videoOutput = nil
        sessionQueue.async {
            if let session = session, session.isRunning {
                session.stopRunning()
            }
        }
    }

    /// Adds a scanner overlay below the focus gesture view.
    func addSubview(_ view: UIView) {
        preview.insertSubview(view, belowSubview: focusView)
    }

    /// Subscribes to interface-orientation changes affecting preview output.
    private func subscribeOrientationChanges() {
        NotificationCenter.default.addObserver(self, selector: #selector(self.onOrientationChanges), name: UIApplication.didChangeStatusBarOrientationNotification, object: nil)
    }

    /// Updates the preview connection after interface orientation changes.
    @objc private func onOrientationChanges() {
        previewLayer?.connection?.videoOrientation = getVideoOrieitation()
    }

    /// Maps the current status-bar orientation to AVFoundation video orientation.
    private func getVideoOrieitation() -> AVCaptureVideoOrientation{
        switch UIApplication.shared.statusBarOrientation {
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

    /// Applies normalized zoom in the inclusive range from `0` to `1`.
    func setZoom(_ value: Double) throws {
        guard let camera = camera else {
            throw MlKitPluginError.cameraIsNotInitialized
        }
        try camera.lockForConfiguration()
        // value in Range from 0 to 1, ios range from 1 to maxAvailableVideoZoomFactor
        let zoom = 1 + CGFloat(value) * 5
        camera.videoZoomFactor = min(zoom, camera.maxAvailableVideoZoomFactor)
        camera.unlockForConfiguration()
    }
    
    /// Observes hardware torch activity and reports it with this view's identity.
    private func observeTorchToggle() {
        torchObserver = camera?.observe(\.isTorchActive, options: .new) { [weak self] _, observable in
            guard let isActive = observable.newValue else { return }
            guard let self = self else { return }
            self.cameraPreviewDelegate?.onToggleTorch(value: isActive, viewId: self.viewId)
        }
    }
}

extension CameraPreview: AVCaptureVideoDataOutputSampleBufferDelegate {
    /// Forwards camera frames to the currently active recognition handler.
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        recognitionHandler?.processVideoOutput(sampleBuffer: sampleBuffer, scaleX: scaleX, scaleY: scaleY, orientation: connection.videoOrientation)
    }
}

extension CameraPreview: FocusViewDelegate {
    /// Requests continuous focus at the current overlay center.
    func onFocus() {
        focusOnCenter(needLock: false)
    }
    
    /// Requests a one-shot focus lock at the current overlay center.
    func onLockFocus() {
        focusOnCenter(needLock: true)
    }
    
    /// Applies continuous or locked focus and exposure at the current focus point.
    private func focusOnCenter(needLock: Bool) {
        guard let camera = camera else {
            return
        }
        do {
            try camera.lockForConfiguration()
            camera.focusPointOfInterest = focusPoint
            camera.exposurePointOfInterest = focusPoint
            if (needLock) {
                camera.exposureMode = .autoExpose
                camera.focusMode = .autoFocus
            } else {
                camera.exposureMode = .continuousAutoExposure
                camera.focusMode = .continuousAutoFocus
            }
            camera.unlockForConfiguration()
        } catch {}
    }
}

extension CameraPreview: UIContainerDelegate {
    /// Recalculates preview geometry and focus coordinates during layout.
    func viewWillLayoutSubviews() {
        self.scaleX = self.preview.frame.width / UIScreen.main.bounds.width
        self.scaleY = self.preview.frame.height / UIScreen.main.bounds.height
        self.previewLayer?.frame = self.preview.frame
        let focusPoint = CameraPreview.calcFocusPoint(preview: preview, offsetX: offsetX, offsetY: offsetY)
        self.focusPoint = focusPoint.normalized()
        focusView.changeFocusPoint(point: focusPoint.position())
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

/// Camera focus point.
fileprivate class FocusPoint {
    private let point: CGPoint
    private let frame: CGRect
    
    /// Creates a normalized point from preview-center offsets.
    init(frame: CGRect, offsetX: CGFloat, offsetY: CGFloat) {
        self.point = CGPoint(x: (frame.midX + frame.midX * offsetX) / frame.maxX, y: (frame.midY + frame.midY * offsetY) / frame.maxY)
        self.frame = frame
    }
    
    /// Returns the coordinates of a focus point.
    func position() -> CGPoint {
        return CGPoint(x: point.x * frame.maxX, y: point.y * frame.maxY)
    }
    
    /// Returns the normalized focus point.
    func normalized() -> CGPoint {
        return point;
    }
}
