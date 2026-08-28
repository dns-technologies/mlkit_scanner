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
        case PluginConstants.captureCameraMethod,
             PluginConstants.releaseCameraMethod:
            result(nil)
        case PluginConstants.initCameraMethod:
            initCamera(arguments: call.arguments, result: result)
        case PluginConstants.disposeMethod:
            dispose(arguments: call.arguments, result: result)
        case PluginConstants.toggleFlashMethod:
            toggleFlash(arguments: call.arguments, result: result)
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
        guard
            let params = arguments as? Dictionary<String, Any?>,
            let viewId = viewId(from: params)
        else {
            handleError(error: MlKitPluginError.invalidArguments, result: result)
            return
        }
        guard let cameraPreview = cameraPreview, cameraPreview.viewId == viewId else {
            handleError(error: MlKitPluginError.cameraIsNotInitialized, result: result)
            return
        }

        // When rebuilding a widget, dispose() is not called,
        // which causes situations where initCamera() can be called multiple times.
        if (isAlreadyInitialized) {
            result(nil)
            return
        }

        var initialZoom: Double?
        if let zoomArgument = params[PluginConstants.initialZoomArgument] {
            guard let zoom = zoomArgument as? Double else {
                handleError(error: MlKitPluginError.invalidArguments, result: result)
                return
            }
            initialZoom = zoom
        }

        if let cropRectArguments = params[PluginConstants.initialCropRectArgument] {
            guard
                let cropRectMap = cropRectArguments as? Dictionary<String, CGFloat>,
                let cropRect = CropRect(arguments: cropRectMap)
            else {
                handleError(error: MlKitPluginError.invalidArguments, result: result)
                return
            }
            setCropArea(rect: cropRect)
        }

        var initialCamera: CameraData?
        if let cameraArguments = params[PluginConstants.initialCameraArgument] {
            guard let cameraMap = cameraArguments as? Dictionary<String, Any?> else {
                handleError(error: MlKitPluginError.invalidArguments, result: result)
                return
            }
            initialCamera = CameraData(arguments: cameraMap)
        }

        isAlreadyInitialized = true
        cameraPreview.initCamera(initialZoom: initialZoom, initialCamera: initialCamera) { [weak self, weak cameraPreview] error in
            DispatchQueue.main.async { [weak self, weak cameraPreview] in
                guard let self = self else {
                    result(FlutterError(
                        code: MlKitPluginError.cameraIsNotInitialized.rawValue,
                        message: MlKitPluginError.cameraIsNotInitialized.localizedDescription,
                        details: nil
                    ))
                    return
                }
                guard self.cameraPreview === cameraPreview else {
                    self.handleError(error: MlKitPluginError.cameraIsNotInitialized, result: result)
                    return
                }
                if let error = error {
                    self.handleError(error: error, result: result)
                    return
                }
                cameraPreview?.cameraPreviewDelegate = self
                result(nil)
            }
        }
    }

    private func toggleFlash(arguments: Any?, result: @escaping FlutterResult) {
        guard ownsCurrentView(arguments) else {
            handleError(error: MlKitPluginError.cameraIsNotInitialized, result: result)
            return
        }
        do {
            try cameraPreview?.toggleFlash()
            result(nil)
        } catch {
            handleError(error: error, result: result)
        }
    }

    private func dispose(arguments: Any?, result: @escaping FlutterResult) {
        guard
            let params = arguments as? Dictionary<String, Any?>,
            let viewId = viewId(from: params)
        else {
            handleError(error: MlKitPluginError.invalidArguments, result: result)
            return
        }
        guard cameraPreview?.viewId == viewId else {
            result(nil)
            return
        }

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
        guard
            let args = arguments as? Dictionary<String, Any?>,
            let type = args["type"] as? Int,
            let delay = args["delay"] as? Int
        else {
            handleError(error: MlKitPluginError.invalidArguments, result: result)
            return
        }
        guard let recognitionType = RecognitionType.init(rawValue: type) else {
            handleError(error: MlKitPluginError.invalidArguments, result: result)
            return
        }
        if recognitionType != recognitionHandler?.type {
            recognitionHandler = recognitionType.createRecognitionHandler(
                delay: delay,
                cropRect: scannerOverlay?.cropRect,
                viewId: cameraPreview.viewId
            )
            recognitionHandler?.delegate = self
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
        guard
            let params = arguments as? Dictionary<String, Any?>,
            ownsCurrentView(params),
            let delay = params[PluginConstants.delayArgument] as? Int
        else {
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
        guard
            let params = arguments as? Dictionary<String, Any?>,
            ownsCurrentView(params),
            let zoom = params[PluginConstants.valueArgument] as? Double
        else {
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
        guard
            let params = arguments as? Dictionary<String, Any?>,
            ownsCurrentView(params),
            let rectArgs = params[PluginConstants.cropRectArgument] as? Dictionary<String, CGFloat>,
            let rect = CropRect(arguments: rectArgs)
        else {
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
            availableCameras.append(camera.toCameraData().toJson())
        }
        result(availableCameras)
    }
    
    private func setCamera(arguments: Any?, result: @escaping FlutterResult) {
        guard
            let cameraArgs = arguments as? Dictionary<String, Int>
        else {
            handleError(error: MlKitPluginError.invalidArguments, result: result)
            return
        }
        do {
            let cameraData = CameraData(arguments: cameraArgs)
            try cameraPreview?.setCamera(cameraData)
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

    private func viewId(from arguments: Dictionary<String, Any?>) -> Int64? {
        guard let value = arguments[PluginConstants.viewIdArgument] as? NSNumber else {
            return nil
        }
        let viewId = value.int64Value
        return viewId >= 0 ? viewId : nil
    }

    private func ownsCurrentView(_ arguments: Any?) -> Bool {
        guard let params = arguments as? Dictionary<String, Any?> else { return false }
        return ownsCurrentView(params)
    }

    private func ownsCurrentView(_ arguments: Dictionary<String, Any?>) -> Bool {
        guard let viewId = viewId(from: arguments) else { return false }
        return cameraPreview?.viewId == viewId
    }
}

extension SwiftMlkitScannerPlugin: FlutterPlatformViewFactory {
    
    public func createArgsCodec() -> FlutterMessageCodec & NSObjectProtocol {
        return FlutterStandardMessageCodec.sharedInstance()
    }
    
    public func create(withFrame frame: CGRect, viewIdentifier viewId: Int64, arguments args: Any?) -> FlutterPlatformView {
        cameraPreview?.dispose()
        recognitionHandler = nil
        scannerOverlay = nil
        isAlreadyInitialized = false

        if let arguments = args as? Dictionary<String, CGFloat>, let width = arguments["width"], let height = arguments["height"] {
            let frame = CGRect(origin: CGPoint.zero, size: CGSize(width: width, height: height))
            cameraPreview = CameraPreview(frame: frame, viewId: viewId)
        } else {
            cameraPreview = CameraPreview(frame: frame, viewId: viewId)
        }
        return cameraPreview!
    }
    
}

extension SwiftMlkitScannerPlugin: RecognitionResultDelegate {
    
    func onRecognition(result: Barcode, viewId: Int64) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.channel.invokeMethod(
                PluginConstants.scanResultMethod,
                arguments: [
                    PluginConstants.viewIdArgument: viewId,
                    PluginConstants.barcodeArgument: result.toJson(),
                ]
            )
        }
    }
    
    func onError(error: Error) {
        // TODO: error check
    }
}

extension SwiftMlkitScannerPlugin: CameraPreviewDelegate {
    func onToggleTorch(value: Bool, viewId: Int64) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.channel.invokeMethod(
                PluginConstants.changeTorchStateMethod,
                arguments: [
                    PluginConstants.viewIdArgument: viewId,
                    PluginConstants.valueArgument: value,
                ]
            )
        }
    }
}
