//
//  CameraPreview.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 04.03.2021.
//

import UIKit
import AVFoundation

/// Deleage of camera preview
protocol CameraPreviewDelegate: NSObject {
    /// Call delegate on change torch state
    func onToggleTorch(value: Bool)
}

class CameraPreview: NSObject, FlutterPlatformView {
    private let preview: Preview
    private var scaleX: CGFloat
    private var scaleY: CGFloat
    private var captureSession: AVCaptureSession?
    private var camera: AVCaptureDevice?
    private var videoOutput: AVCaptureVideoDataOutput?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private let sessionQueue = DispatchQueue.global(qos: .userInitiated)
    private var torchObserver: NSKeyValueObservation?
    private let heightConstraint: NSLayoutConstraint
    private let widthConstraint: NSLayoutConstraint
    private let focusView: CenterFocusView
    weak var recognitionHandler: RecognitionHandler?
    weak var cameraPreviewDelegate: CameraPreviewDelegate?
    
    init(frame: CGRect) {
        preview = Preview(frame: frame)
        scaleX = frame.width / UIScreen.main.bounds.width
        scaleY = frame.height / UIScreen.main.bounds.height
        focusView = CenterFocusView(frame: preview.frame)
        preview.translatesAutoresizingMaskIntoConstraints = false
        heightConstraint = preview.heightAnchor.constraint(equalToConstant: frame.height)
        widthConstraint = preview.widthAnchor.constraint(equalToConstant: frame.width)
        super.init()
        heightConstraint.isActive = true
        widthConstraint.isActive = true
        preview.delegate = self
        focusView.delegate = self
        subscribeCaptureSessionStopNotification()
    }
    
    private func subscribeCaptureSessionStopNotification() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(self.onCaptureSessionStart),
            name: .AVCaptureSessionDidStartRunning,
            object: nil)
        
        
    }
    
    @objc private func onCaptureSessionStart() {
        focusOnCenter(needLock: false)
        DispatchQueue.main.async { [weak self] in
            self?.focusView.cancelLockFocus()
        }
    }
    
    func view() -> UIView {
        return preview
    }
    
    /// Initialization of the device camera. Initialization runs in non UI thread. 
    /// Result of init caling with closure `completion`.
    /// Can return `Error` on problem with device camera or app doesn't have permission to use camera. 
    func initCamera(completion: @escaping (Error?) -> ()) {
        do {
            try checkPermission()
            
            camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
            guard let camera = camera else {
                completion(MlKitPluginError.initCameraError)
                return
            }
            
            let input = try AVCaptureDeviceInput.init(device: camera)
            captureSession = AVCaptureSession()
            captureSession?.sessionPreset = .hd1280x720
            captureSession?.addInput(input)
        } catch {
            completion(error)
            return
        }
        
        previewLayer = AVCaptureVideoPreviewLayer(session: captureSession!)
        previewLayer?.videoGravity = .resizeAspectFill
        previewLayer?.connection?.videoOrientation = getVideoOrieitation()
        previewLayer?.frame = preview.frame
        preview.layer.insertSublayer(previewLayer!, at: 0)
        addFocusView()
        
        subscribeOrientationChanges()
        self.observeTorchToggle()
        sessionQueue.async {  [weak self] in
            guard let self = self , let session = self.captureSession else {
                completion(MlKitPluginError.cameraIsNotInitialized)
                return
            }
            self.videoOutput = AVCaptureVideoDataOutput()
            self.videoOutput?.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA)]
            self.videoOutput?.setSampleBufferDelegate(self, queue: .global(qos: .userInitiated))
            session.addOutput(self.videoOutput!)
            session.startRunning()
            completion(nil)
        }
    }
    
    private func addFocusView() {
        preview.addSubview(focusView)
    }
        
    /// Toggle of the device flash. Throws `MlKitPluginError.cameraIsNotInitialized` if try toggle without camera initialization,
    // or `MlKitPluginError.deviceHasNotFlash` if device doesn't have flash.
    func toggleFlash() throws {
        guard let session = captureSession, session.isRunning, let camera = camera, camera.isConnected else {
            throw MlKitPluginError.cameraIsNotInitialized
        }
        if (camera.hasTorch) {
            try camera.lockForConfiguration()
            camera.torchMode = camera.torchMode == AVCaptureDevice.TorchMode.off ? .on : .off
            camera.unlockForConfiguration()
        } else {
            throw MlKitPluginError.deviceHasNotFlash
        }
    }
    
    /// Update constraints of the `CameraPreview`.
    func updateConstraints(width: CGFloat, height: CGFloat) {
        heightConstraint.constant = height
        widthConstraint.constant = width
        preview.updateConstraints()
    }
    
    /// Pause a `CaptureSession`, runs in non UI thread. 
    /// Result caling by closure `completion`.
    func pauseCamera(completion: @escaping () -> ()) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            if let session = self.captureSession, session.isRunning {
                session.stopRunning()
            }
            completion()
        }
    }
    
    /// Resume a `CaptureSession`, runs in non UI thread. 
    /// Result caling by closure `completion`.
    /// Can return `Error` on try resume non initialized camera.
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
    
    /// Release device camera resources. Must call this method when camera is no longer needed.
    func dispose() {
        torchObserver?.invalidate()
        NotificationCenter.default.removeObserver(self)
        previewLayer?.removeFromSuperlayer()
        previewLayer = nil
        captureSession?.stopRunning()
        captureSession = nil
        camera = nil
        videoOutput = nil
    }
    
    func addSubview(_ view: UIView) {
        preview.insertSubview(view, belowSubview: focusView)
    }
    
    private func subscribeOrientationChanges() {
        NotificationCenter.default.addObserver(self, selector: #selector(self.onOrientationChanges), name: UIApplication.didChangeStatusBarOrientationNotification, object: nil)
    }
    
    @objc private func onOrientationChanges() {
        previewLayer?.connection?.videoOrientation = getVideoOrieitation()
    }
    
    private func checkPermission() throws {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        
        switch status {
        case .authorized:
            return
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                /// TODO - Доработать логику результата запросов прав
                return
            }
        default:
            throw MlKitPluginError.authorizationCameraError
        }
    }
    
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
    
    func setZoom(_ value: Double) throws {
        guard let session = captureSession, session.isRunning, let camera = camera, camera.isConnected else {
            throw MlKitPluginError.cameraIsNotInitialized
        }
        try camera.lockForConfiguration()
        // value in Range from 0 to 1, ios range from 1 to maxAvailableVideoZoomFactor
        let zoom = 1 + CGFloat(value) * 5
        camera.videoZoomFactor = min(zoom, camera.maxAvailableVideoZoomFactor)
        camera.unlockForConfiguration()
    }
    
    private func observeTorchToggle() {
        torchObserver = camera?.observe(\.isTorchActive, options: .new) { [weak self] _, observable in
            guard let isActive = observable.newValue else { return }
            self?.cameraPreviewDelegate?.onToggleTorch(value: isActive)
        }
    }
}

extension CameraPreview: AVCaptureVideoDataOutputSampleBufferDelegate {
    
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        recognitionHandler?.proccessVideoOutput(sampleBuffer: sampleBuffer, scaleX: scaleX, scaleY: scaleY, orientation: connection.videoOrientation)
    }
}

extension CameraPreview: CenterFocusViewDelegate {
    
    func onFocus() {
        focusOnCenter(needLock: false)
    }
    
    func onLockFocus() {
        focusOnCenter(needLock: true)
    }
    
    private func focusOnCenter(needLock: Bool) {
        guard let camera = camera else {
            return
        }
        let point = CGPoint(x: 0.5, y: 0.5)
        do {
            try camera.lockForConfiguration()
            camera.focusPointOfInterest = point
            camera.exposurePointOfInterest = point
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

extension CameraPreview: PreviewDelegate {
    
    func viewWillLayoutSubviews() {
        self.scaleX = self.preview.frame.width / UIScreen.main.bounds.width
        self.scaleY = self.preview.frame.height / UIScreen.main.bounds.height
        self.previewLayer?.frame = self.preview.frame
    }
}

fileprivate protocol PreviewDelegate: NSObject {
    func viewWillLayoutSubviews()
}

fileprivate class Preview : UIView {

    weak var delegate: PreviewDelegate?
    
    override func layoutSubviews() {
        super.layoutSubviews()
        delegate?.viewWillLayoutSubviews()
    }
    
    override init(frame: CGRect) {
        super.init(frame: frame)
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}
