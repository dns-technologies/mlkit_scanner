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
    private let preview: UIContainer
    private var scaleX, scaleY: CGFloat
    private var offsetX, offsetY: CGFloat
    private var focusPoint: CGPoint
    private var captureSession: AVCaptureSession?
    private var camera: AVCaptureDevice?
    private var videoOutput: AVCaptureVideoDataOutput?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private let sessionQueue = DispatchQueue.global(qos: .userInitiated)
    private var torchObserver: NSKeyValueObservation?
    
    private let focusView: FocusView
    weak var recognitionHandler: RecognitionHandler?
    weak var cameraPreviewDelegate: CameraPreviewDelegate?
    
    init(frame: CGRect, offsetX: CGFloat = 0, offsetY: CGFloat = 0) {
        preview = UIContainer(frame: frame)
        (scaleX, scaleY) = (frame.width / UIScreen.main.bounds.width, frame.height / UIScreen.main.bounds.height)
        (self.offsetX, self.offsetY) = (offsetX, offsetY)
        let focusPoint = CameraPreview.calcFocusPoint(preview: preview, offsetX: offsetX, offsetY: offsetY)
        self.focusPoint = focusPoint.normalized()
        focusView = FocusView(frame: preview.frame, point: focusPoint.position())
        super.init()
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
            
            camera = createDevice()
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
    
    private func createDevice() -> AVCaptureDevice? {
        if #available(iOS 13.0, *) {
            if let device = AVCaptureDevice.default(.builtInTripleCamera, for: .video, position: .back) {
                return device
            }
        }
        
        if let device = AVCaptureDevice.default(.builtInDualCamera, for: .video, position: .back) {
            return device
        }
        
        return AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
    }
    
    private func addFocusView() {
        preview.addSubview(focusView)
    }
        
    /// Calculates the focus point relative to center of the screen with offsets `offsetX` and `offsetY`
    private class func calcFocusPoint(preview: UIContainer, offsetX: CGFloat, offsetY: CGFloat) -> FocusPoint {
        return FocusPoint(frame: preview.frame, offsetX: offsetX, offsetY: offsetY)
    }
    
    /// Сhanges focus around the center
    func changeFocusCenter(offsetX: CGFloat, offsetY: CGFloat) {
        (self.offsetX, self.offsetY) = (offsetX, offsetY)
        let focusPoint = CameraPreview.calcFocusPoint(preview: preview, offsetX: offsetX, offsetY: offsetY)
        self.focusPoint = focusPoint.normalized()
        focusView.changeFocusPoint(point: focusPoint.position())
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
        preview.updateSizeConstraints(width: width, height: height)
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

extension CameraPreview: FocusViewDelegate {
    
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
    
    func viewWillLayoutSubviews() {
        self.scaleX = self.preview.frame.width / UIScreen.main.bounds.width
        self.scaleY = self.preview.frame.height / UIScreen.main.bounds.height
        self.previewLayer?.frame = self.preview.frame
        let focusPoint = CameraPreview.calcFocusPoint(preview: preview, offsetX: offsetX, offsetY: offsetY)
        self.focusPoint = focusPoint.normalized()
        focusView.changeFocusPoint(point: focusPoint.position())
    }
}

fileprivate protocol UIContainerDelegate: NSObject {
    /// Called to notify the UIContainerDelegate that view is about to layout its subviews.
    func viewWillLayoutSubviews()
}

/// Empty container. Depends on height and width constraints.
fileprivate class UIContainer : UIView {

    private var heightConstraint: NSLayoutConstraint!
    private var widthConstraint: NSLayoutConstraint!
    weak var delegate: UIContainerDelegate?
    
    override init(frame: CGRect) {
        super.init(frame: frame)
        translatesAutoresizingMaskIntoConstraints = false
        heightConstraint = heightAnchor.constraint(equalToConstant: frame.height)
        widthConstraint = widthAnchor.constraint(equalToConstant: frame.width)
        heightConstraint.isActive = true
        widthConstraint.isActive = true
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
    
    override func layoutSubviews() {
        super.layoutSubviews()
        delegate?.viewWillLayoutSubviews()
    }
    
    func updateSizeConstraints(width: CGFloat, height: CGFloat) {
        heightConstraint.constant = height
        widthConstraint.constant = width
        updateConstraints()
    }
}

/// Camera focus point.
fileprivate class FocusPoint {
    private let point: CGPoint
    private let frame: CGRect
    
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
