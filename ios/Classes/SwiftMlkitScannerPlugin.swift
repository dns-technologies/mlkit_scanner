import Flutter
import AVFoundation
import UIKit
import MLKitBarcodeScanning
import MLKitVision

public class SwiftMlkitScannerPlugin: NSObject, FlutterPlugin {
    
    private let channel: FlutterMethodChannel
    private var cameraPreview: CameraPreview?
    private var cameraUtil: CameraUtil
    private var recognitionHandler: RecognitionHandler?
    private var scannerOverlay: ScannerOverlay?
    private var isAlreadyInitialized: Bool = false
    
    init(channel: FlutterMethodChannel) {
        self.channel = channel
        self.cameraUtil = CameraUtil()
        super.init()
    }
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "mlkit_channel", binaryMessenger: registrar.messenger())
        let instance = SwiftMlkitScannerPlugin(channel: channel)
        registrar.addMethodCallDelegate(instance, channel: channel)
        registrar.register(instance, withId: "mlkit/camera_preview")
    }
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case PluginConstants.initCameraMethod:
            initCamera(arguments: call.arguments, result: result)
        case PluginConstants.disposeMethod:
            dispose(result: result)
        case PluginConstants.toggleFlashMethod:
            toggleFlash(result: result)
        case PluginConstants.startScanMethod:
            startScan(arguments: call.arguments, result: result)
        case PluginConstants.cancelScanMethod:
            cancelScan(result: result)
        case PluginConstants.setScanDelayMethod:
            setScanDelayMethod(arguments: call.arguments, result: result)
        case PluginConstants.changeConstraintsMethod:
            updateConstraints(arguments: call.arguments, result: result)
        case PluginConstants.resumeCameraMethod:
            resumeCamera(result: result)
        case PluginConstants.pauseCameraMethod:
            pauseCamera(result: result)
        case PluginConstants.setZoomMethod:
            setZoom(arguments: call.arguments, result: result)
        case PluginConstants.setCropAreaMethod:
            handleSetCropArea(arguments: call.arguments, result: result)
        case PluginConstants.getIosAvailableCamerasMethod:
            getAvailableCameras(result: result)
        case PluginConstants.setIosCameraMethod:
            setCamera(arguments: call.arguments, result: result)
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    private func initCamera(arguments: Any?, result: @escaping FlutterResult) {
        // When rebuilding a widget, dispose() is not called,
        // which causes situations where initCamera() can be called multiple times.
        if (isAlreadyInitialized == true) {
            return
        }

        guard let params = arguments as? Dictionary<String, Any?>? else {
            handleError(error: MlKitPluginError.invalidArguments, result: result)
            return
        }
        let initialScannerParameters = InitialScannerParameters(arguments: params)

        if (initialScannerParameters?.initialCropRect != nil) {
            setCropArea(rect: initialScannerParameters!.initialCropRect!)
        }

        guard let cameraPreview = cameraPreview else {
            self.handleError(error: MlKitPluginError.cameraIsNotInitialized, result: result)
            return
        }
        cameraPreview.initCamera(initialZoom: initialScannerParameters?.initialZoom, initialCamera: initialScannerParameters?.initialCamera) { [weak self] error in
            if let error = error {
                self?.handleError(error: error, result: result)
            } else {
                self?.cameraPreview?.cameraPreviewDelegate = self
                result(nil)
            }
        }
        isAlreadyInitialized = true;
    }

    private func toggleFlash(result: @escaping FlutterResult) {
        do {
            try cameraPreview?.toggleFlash()
            result(nil)
        } catch {
            handleError(error: error, result: result)
        }
    }

    private func dispose(result: @escaping FlutterResult) {
        cameraPreview?.dispose()
        cameraPreview = nil
        scannerOverlay = nil
        recognitionHandler = nil
        isAlreadyInitialized = false
        result(nil)
    }

    private func updateConstraints(arguments: Any?, result: @escaping FlutterResult) {
        if let args = arguments as? Dictionary<String, CGFloat>, let width = args["width"], let height = args["height"] {
            cameraPreview?.updateConstraints(width: width, height: height)
            result(nil)
        } else {
            handleError(error: MlKitPluginError.invalidArguments, result: result)
        }
    }

    private func startScan(arguments: Any?, result: @escaping FlutterResult) {
        guard let cameraPreview = cameraPreview else {
            handleError(error: MlKitPluginError.cameraIsNotInitialized, result: result)
            return
        }
        guard let args = arguments as? Dictionary<String, Any>, let type = args["type"] as? Int, let delay = args["delay"] as? Int else {
            handleError(error: MlKitPluginError.invalidArguments, result: result)
            return
        }
        guard let recognitionType = RecognitionType.init(rawValue: type) else {
            handleError(error: MlKitPluginError.invalidArguments, result: result)
            return
        }
        if recognitionType != recognitionHandler?.type {
            recognitionHandler = recognitionType.createRecognitionHandler(delay: delay, cropRect: scannerOverlay?.cropRect)
            recognitionHandler!.delegate = self
            cameraPreview.recognitionHandler = recognitionHandler
            scannerOverlay?.isActive = true
        }
        result(nil)

    }

    private func cancelScan(result: @escaping FlutterResult) {
        recognitionHandler = nil
        scannerOverlay?.isActive = false
        result(nil)
    }

    private func setScanDelayMethod(arguments: Any?, result: @escaping FlutterResult) {
        guard let delay = arguments as? Int else {
            handleError(error: MlKitPluginError.invalidArguments, result: result)
            return
        }
        recognitionHandler?.setDelay(delay: delay)
        result(nil)
    }

    private func pauseCamera(result: @escaping FlutterResult) {
        guard let cameraPreview = cameraPreview else {
            result(nil)
            return
        }
        cameraPreview.pauseCamera() {
            result(nil)
        }
    }

    private func resumeCamera(result: @escaping FlutterResult) {
        guard let cameraPreview = cameraPreview else {
            handleError(error: MlKitPluginError.cameraIsNotInitialized, result: result)
            return
        }
        cameraPreview.resumeCamera() { [weak self] error in
            if let error = error {
                self?.handleError(error: error, result: result)
            } else {
                result(nil)
            }
        }
    }

    private func setZoom(arguments: Any?, result: @escaping FlutterResult) {
        guard let zoom = arguments as? Double else {
            handleError(error: MlKitPluginError.invalidArguments, result: result)
            return
        }
        do {
            try cameraPreview?.setZoom(zoom)
            result(nil)
        } catch {
            handleError(error: error, result: result)
        }
    }

    private func handleSetCropArea(arguments: Any?, result: @escaping FlutterResult) {
        guard let rectArgs = arguments as? Dictionary<String, CGFloat>, let rect = CropRect(arguments: rectArgs) else {
            handleError(error: MlKitPluginError.invalidArguments, result: result)
            return
        }
        setCropArea(rect: rect)
        result(nil)
    }

    private func setCropArea(rect: CropRect) {
        guard let camera = cameraPreview else {
            return
        }
        recognitionHandler?.updateCropRect(cropRect: rect)
        cameraPreview?.changeFocusCenter(offsetX: rect.offsetX, offsetY: rect.offsetY)
        if let overlay = scannerOverlay {
            overlay.updateCropRect(rect: rect)
        } else {
            scannerOverlay = ScannerOverlay(cropRect: rect)
            camera.addSubview(scannerOverlay!)
        }
    }

    private func getAvailableCameras(result: @escaping FlutterResult) {
        let cameras = cameraUtil.getAvailableCameras()
        var availableCameras = [[String: Any]]()

        for camera in cameras {
            guard camera.isSupported else {
                continue
            }
            availableCameras.append(camera.toJson())
        }
        result(availableCameras)
    }
    
    private func setCamera(arguments: Any?, result: @escaping FlutterResult) {
        guard
            let cameraArgs = arguments as? Dictionary<String, Int>,
            let positionCode = cameraArgs["position"] as Int?,
            let typeCode = cameraArgs["type"] as Int?,
            let position = AVCaptureDevice.Position.fromCode(positionCode),
            let deviceType = AVCaptureDevice.DeviceType.fromCode(typeCode)
        else {
            handleError(error: MlKitPluginError.invalidArguments, result: result)
            return
        }
        do {
            try cameraPreview?.setCamera(deviceType: deviceType, position: position)
            result(nil)
        } catch {
            handleError(error: error, result: result)
        }
    }

    private func handleError(error: Error, result: @escaping FlutterResult) {
        if let err = error as? MlKitPluginError {
            result(FlutterError(code: err.rawValue, message: err.localizedDescription, details: nil))
        } else {
            result(FlutterError(code: "0", message: error.localizedDescription, details: nil))
        }
    }
}

extension SwiftMlkitScannerPlugin: FlutterPlatformViewFactory {
    
    public func createArgsCodec() -> FlutterMessageCodec & NSObjectProtocol {
        return FlutterStandardMessageCodec.sharedInstance()
    }
    
    public func create(withFrame frame: CGRect, viewIdentifier viewId: Int64, arguments args: Any?) -> FlutterPlatformView {
        if let arguments = args as? Dictionary<String, CGFloat>, let width = arguments["width"], let height = arguments["height"] {
            let frame = CGRect(origin: CGPoint.zero, size: CGSize(width: width, height: height))
            cameraPreview = CameraPreview(frame: frame)
        } else {
            cameraPreview = CameraPreview(frame: frame)
        }
        return cameraPreview!
    }
    
}

extension SwiftMlkitScannerPlugin: RecognitionResultDelegate {
    
    func onRecognition(result: String) {
        channel.invokeMethod(PluginConstants.scanResultMethod, arguments: result)
    }
    
    func onError(error: Error) {
        // TODO: error check
    }
}

extension SwiftMlkitScannerPlugin: CameraPreviewDelegate {
    func onToggleTorch(value: Bool) {
        channel.invokeMethod(PluginConstants.changeTorchStateMethod, arguments: value)
    }
}
