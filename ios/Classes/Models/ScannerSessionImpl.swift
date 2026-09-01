import Foundation
import MLKitBarcodeScanning

/// Owns all iOS scanner platform-view state and one explicit camera binding.
final class ScannerSessionImpl: ScannerSession {
    private var views: [Int64: ScannerViewState] = [:]
    private let onScanResult: (Int64, Barcode) -> Void
    private let onTorchChanged: (Int64, Bool) -> Void
    private var isReleased = false

    /// Creates a scanner session with view-scoped native event callbacks.
    init(
        onScanResult: @escaping (Int64, Barcode) -> Void,
        onTorchChanged: @escaping (Int64, Bool) -> Void
    ) {
        self.onScanResult = onScanResult
        self.onTorchChanged = onTorchChanged
    }

    /// Creates and registers a native preview without capturing the camera.
    func createView(
        frame: CGRect,
        viewId: Int64,
        registration: ScannerViewRegistration
    ) -> CameraPreview {
        precondition(!isReleased, "Cannot add a scanner view to a released session")
        precondition(views[viewId] == nil, "Scanner platform view \(viewId) already exists")

        let viewFrame = registration.size.map {
            CGRect(origin: .zero, size: $0)
        } ?? frame
        let view = CameraPreview(frame: viewFrame, viewId: viewId) { [weak self] disposedViewId in
            self?.disposeView(disposedViewId)
        }
        views[viewId] = ScannerViewState(
            viewId: viewId,
            view: view,
            registration: registration
        )
        return view
    }

    /// Transfers ownership, awaits that view's initialization, and restores retained state.
    func captureCamera(viewId: Int64, completion: @escaping ScannerSessionCompletion) {
        do {
            let viewState = try requireView(viewId)
            let previousCapture = capturedViewState()
            let previousView = previousCapture !== viewState ? previousCapture?.view : nil

            views.values.forEach { state in
                state.isCameraOwner = state === viewState
                if state !== viewState {
                    state.cameraTransitionReady = false
                }
            }
            viewState.cameraTransitionReady = previousView == nil
            if previousCapture !== viewState {
                if let previousCapture = previousCapture {
                    deactivateView(previousCapture)
                }
            } else {
                viewState.configurationApplied = false
                applyScanState(viewState)
            }

            // Dependency initialization is independent from the physical hand-off. It may
            // continue while the previous session stops, but resume is gated by the exact
            // transition state stored on this view.
            activateView(viewState, completion: completion)
            previousView?.pauseCamera { [weak self, weak view = viewState.view] in
                guard let self = self else { return }
                self.onMain {
                    guard let view = view else { return }
                    if self.isCurrentCapture(viewState.viewId) {
                        viewState.cameraTransitionReady = true
                    }
                    if viewState.cameraInitialized {
                        self.resumeInitializedView(viewState, view: view)
                    }
                }
            }
        } catch {
            complete(completion, error: error)
        }
    }

    /// Releases ownership if it is still held by the referenced view.
    func releaseCamera(viewId: Int64, completion: @escaping () -> Void) {
        guard let viewState = views[viewId], viewState.isCameraOwner else {
            complete(completion)
            return
        }

        viewState.isCameraOwner = false
        viewState.cameraTransitionReady = false
        deactivateView(viewState)
        guard let view = viewState.view else {
            complete(completion)
            return
        }
        view.pauseCamera { [weak self] in
            self?.complete(completion)
        }
    }

    /// Pauses camera intent without deleting the referenced view's state.
    func pauseCamera(viewId: Int64, completion: @escaping () -> Void) {
        guard let viewState = views[viewId] else {
            complete(completion)
            return
        }
        viewState.cameraRequested = false
        viewState.cameraTransitionReady = false
        deactivateView(viewState)

        guard viewState.isCameraOwner, let view = viewState.view else {
            complete(completion)
            return
        }
        view.pauseCamera { [weak self, weak view] in
            guard let self = self else { return }
            self.onMain {
                if self.isCurrentCapture(viewState.viewId) {
                    viewState.cameraTransitionReady = true
                }
                if viewState.cameraRequested,
                   viewState.cameraInitialized,
                   let view = view {
                    self.resumeInitializedView(viewState, view: view)
                }
                self.complete(completion)
            }
        }
    }

    /// Resumes retained camera and recognition intent for the owning view.
    func resumeCamera(viewId: Int64, completion: @escaping ScannerSessionCompletion) {
        do {
            let viewState = try requireView(viewId)
            viewState.cameraRequested = true
            guard viewState.isCameraOwner else {
                complete(completion, error: nil)
                return
            }
            activateView(viewState, completion: completion)
        } catch {
            complete(completion, error: error)
        }
    }

    /// Toggles retained torch state and applies it when the view is active.
    func toggleFlash(viewId: Int64) throws {
        let viewState = try requireView(viewId)
        let enabled = viewState.torchEnabled != true

        if canApplyControls(viewState) {
            try viewState.view?.setFlash(enabled)
        }
        viewState.torchEnabled = enabled
    }

    /// Starts recognition with the iOS initial and successful-result cooldown.
    func startScan(viewId: Int64, type: RecognitionType, delay: Int) throws {
        let viewState = try requireView(viewId)
        viewState.recognitionType = type
        viewState.scanDelay = delay
        viewState.scanRequestedByView = true
        applyScanState(viewState)
    }

    /// Stops recognition requested by a view without pausing its camera.
    func cancelScan(viewId: Int64) {
        guard let viewState = views[viewId] else { return }
        viewState.scanRequestedByView = false
        applyScanState(viewState)
    }

    /// Updates the successful-recognition cooldown retained by a view.
    func updateScanPeriod(viewId: Int64, delay: Int) throws {
        let viewState = try requireView(viewId)
        viewState.scanDelay = delay
        if canApplyControls(viewState) {
            viewState.recognitionHandler?.setDelay(delay: delay)
        }
    }

    /// Updates the absolute zoom ratio retained by a view.
    func setZoomRatio(viewId: Int64, value: Double) throws {
        let viewState = try requireView(viewId)
        if canApplyControls(viewState) {
            try viewState.view?.setZoomRatio(value)
        }
        viewState.zoomRatio = value
    }

    /// Updates normalized recognition geometry retained by a view.
    func setCropArea(viewId: Int64, cropRect: CropRect) throws {
        let viewState = try requireView(viewId)
        viewState.cropArea = cropRect
        if canApplyControls(viewState) {
            viewState.recognitionHandler?.updateCropRect(cropRect: cropRect)
            applyCropArea(viewState)
        }
    }

    /// Updates the camera retained by a view.
    func setCamera(viewId: Int64, camera: CameraData) throws {
        let viewState = try requireView(viewId)
        viewState.camera = camera
        viewState.configurationApplied = false
        guard canApplyControls(viewState, requireConfiguration: false) else { return }

        guard let view = viewState.view else {
            throw MlKitPluginError.cameraIsNotInitialized
        }
        try view.setCamera(camera)
        try applyConfiguration(viewState, applyCameraSelection: false)
    }

    /// Releases all registered views and scanner resources exactly once.
    func release() {
        guard !isReleased else { return }
        isReleased = true

        let retainedViews = Array(views.values)
        views.removeAll()
        retainedViews.forEach { viewState in
            viewState.captureCompletions.forEach {
                complete($0, error: MlKitPluginError.cameraIsNotInitialized)
            }
            viewState.captureCompletions.removeAll()
            viewState.recognitionHandler = nil
            viewState.scannerOverlay?.removeFromSuperview()
            viewState.view?.dispose()
        }
    }

    /// Starts or resumes capture while the requested view still owns the camera.
    private func activateView(
        _ viewState: ScannerViewState,
        completion: @escaping ScannerSessionCompletion
    ) {
        guard isCurrentCapture(viewState.viewId), viewState.cameraRequested else {
            complete(completion, error: nil)
            return
        }
        guard let view = viewState.view else {
            complete(completion, error: MlKitPluginError.cameraIsNotInitialized)
            return
        }

        view.cameraPreviewDelegate = self
        viewState.captureCompletions.append(completion)
        if viewState.cameraInitialized {
            resumeInitializedView(viewState, view: view)
            return
        }

        guard !viewState.cameraInitializing else { return }
        viewState.cameraInitializing = true
        view.initCamera { [weak self, weak view] error in
            guard let self = self else { return }
            self.onMain {
                viewState.cameraInitializing = false
                guard let view = view else {
                    let completions = viewState.captureCompletions
                    viewState.captureCompletions.removeAll()
                    completions.forEach {
                        self.complete($0, error: MlKitPluginError.cameraIsNotInitialized)
                    }
                    return
                }
                if error == nil {
                    viewState.cameraInitialized = true
                }
                if let error = error {
                    let completions = viewState.captureCompletions
                    viewState.captureCompletions.removeAll()
                    self.finishActivation(
                        viewState,
                        view: view,
                        applyCameraSelection: true,
                        error: error,
                        completions: completions
                    )
                } else {
                    self.resumeInitializedView(viewState, view: view)
                }
            }
        }
    }

    /// Shares one resume operation and starts capture only for the current owner.
    private func resumeInitializedView(
        _ viewState: ScannerViewState,
        view: CameraPreview
    ) {
        guard !viewState.cameraResuming else { return }
        guard isCurrentCapture(viewState.viewId), viewState.cameraRequested else {
            let completions = viewState.captureCompletions
            viewState.captureCompletions.removeAll()
            completions.forEach { complete($0, error: nil) }
            return
        }
        guard viewState.cameraTransitionReady else { return }

        viewState.cameraResuming = true
        view.resumeCamera { [weak self, weak view] error in
            guard let self = self else { return }
            self.onMain {
                viewState.cameraResuming = false
                let completions = viewState.captureCompletions
                viewState.captureCompletions.removeAll()
                guard let view = view else {
                    completions.forEach {
                        self.complete($0, error: MlKitPluginError.cameraIsNotInitialized)
                    }
                    return
                }
                self.finishActivation(
                    viewState,
                    view: view,
                    applyCameraSelection: true,
                    error: error,
                    completions: completions
                )
            }
        }
    }

    /// Validates an asynchronous start and restores the view's retained configuration.
    private func finishActivation(
        _ viewState: ScannerViewState,
        view: CameraPreview,
        applyCameraSelection: Bool,
        error: Error?,
        completions: [ScannerSessionCompletion]
    ) {
        if let error = error {
            viewState.isCameraOwner = false
            viewState.cameraTransitionReady = false
            deactivateView(viewState)
            view.pauseCamera {}
            completions.forEach { complete($0, error: error) }
            return
        }
        guard isCurrentCapture(viewState.viewId),
              viewState.cameraRequested,
              viewState.cameraTransitionReady else {
            view.pauseCamera {}
            completions.forEach { complete($0, error: nil) }
            return
        }

        do {
            try applyConfiguration(
                viewState,
                applyCameraSelection: applyCameraSelection
            )
            completions.forEach { complete($0, error: nil) }
        } catch {
            viewState.isCameraOwner = false
            viewState.cameraTransitionReady = false
            deactivateView(viewState)
            view.pauseCamera {}
            completions.forEach { complete($0, error: error) }
        }
    }

    /// Applies retained camera selection, zoom, torch, crop, and scan state.
    private func applyConfiguration(
        _ viewState: ScannerViewState,
        applyCameraSelection: Bool
    ) throws {
        guard canApplyControls(viewState, requireConfiguration: false),
              let view = viewState.view else {
            return
        }

        if applyCameraSelection, let camera = viewState.camera {
            try view.setCamera(camera)
        }
        view.resetFocus()
        if let zoomRatio = viewState.zoomRatio {
            try view.setZoomRatio(zoomRatio)
        }
        if let torchEnabled = viewState.torchEnabled {
            do {
                try view.setFlash(torchEnabled)
            } catch MlKitPluginError.deviceHasNotFlash where torchEnabled {
                viewState.torchEnabled = false
                try view.setFlash(false)
                onTorchChanged(viewState.viewId, false)
            }
        }
        applyCropArea(viewState)
        viewState.configurationApplied = true
        applyScanState(viewState)
    }

    /// Applies retained recognition geometry to focus and visor overlays.
    private func applyCropArea(_ viewState: ScannerViewState) {
        guard let cropArea = viewState.cropArea, let view = viewState.view else { return }
        view.changeFocusCenter(offsetX: cropArea.offsetX, offsetY: cropArea.offsetY)

        if let overlay = viewState.scannerOverlay {
            overlay.updateCropRect(rect: cropArea)
        } else {
            let overlay = ScannerOverlay(cropRect: cropArea)
            viewState.scannerOverlay = overlay
            view.addSubview(overlay)
        }
    }

    /// Reconciles scan intent with ownership and camera readiness.
    private func applyScanState(_ viewState: ScannerViewState) {
        let shouldScan = canApplyControls(viewState) && viewState.scanRequestedByView
        guard shouldScan,
              let type = viewState.recognitionType,
              let delay = viewState.scanDelay else {
            viewState.view?.recognitionHandler = nil
            viewState.scannerOverlay?.isActive = false
            return
        }

        let handler: RecognitionHandler
        if let currentHandler = viewState.recognitionHandler,
           currentHandler.type == type {
            handler = currentHandler
            handler.setDelay(delay: delay)
            if let cropArea = viewState.cropArea {
                handler.updateCropRect(cropRect: cropArea)
            }
        } else {
            handler = type.createRecognitionHandler(
                delay: delay,
                cropRect: viewState.cropArea,
                viewId: viewState.viewId
            )
            handler.delegate = self
            viewState.recognitionHandler = handler
        }
        viewState.view?.recognitionHandler = handler
        viewState.scannerOverlay?.isActive = true
    }

    /// Stops recognition and marks controls for restoration without deleting state.
    private func deactivateView(_ viewState: ScannerViewState) {
        viewState.configurationApplied = false
        viewState.view?.recognitionHandler = nil
        viewState.scannerOverlay?.isActive = false
    }

    /// Removes state and pending completions for a disposed native platform view.
    private func disposeView(_ viewId: Int64) {
        onMain { [weak self] in
            guard let self = self, let viewState = self.views.removeValue(forKey: viewId) else {
                return
            }
            viewState.isCameraOwner = false
            viewState.cameraTransitionReady = false
            viewState.captureCompletions.forEach {
                self.complete($0, error: MlKitPluginError.cameraIsNotInitialized)
            }
            viewState.captureCompletions.removeAll()
            viewState.recognitionHandler = nil
            viewState.scannerOverlay?.removeFromSuperview()
        }
    }

    /// Returns a registered live view or throws a camera-not-initialized error.
    private func requireView(_ viewId: Int64) throws -> ScannerViewState {
        guard !isReleased, let viewState = views[viewId], viewState.view != nil else {
            throw MlKitPluginError.cameraIsNotInitialized
        }
        return viewState
    }

    /// Returns the one registered view that currently owns the camera.
    private func capturedViewState() -> ScannerViewState? {
        return views.values.first { $0.isCameraOwner }
    }

    /// Checks camera ownership again after asynchronous work.
    private func isCurrentCapture(_ viewId: Int64) -> Bool {
        return !isReleased && views[viewId]?.isCameraOwner == true
    }

    /// Returns whether retained controls may be applied to this view now.
    private func canApplyControls(
        _ viewState: ScannerViewState,
        requireConfiguration: Bool = true
    ) -> Bool {
        return !isReleased
            && viewState.isCameraOwner
            && viewState.cameraTransitionReady
            && viewState.cameraInitialized
            && viewState.cameraRequested
            && (!requireConfiguration || viewState.configurationApplied)
    }

    /// Executes state mutation immediately on main or dispatches it there.
    private func onMain(_ operation: @escaping () -> Void) {
        if Thread.isMainThread {
            operation()
        } else {
            DispatchQueue.main.async(execute: operation)
        }
    }

    /// Completes an error-aware session operation on the main thread.
    private func complete(
        _ completion: @escaping ScannerSessionCompletion,
        error: Error?
    ) {
        onMain { completion(error) }
    }

    /// Completes a nonthrowing session operation on the main thread.
    private func complete(_ completion: @escaping () -> Void) {
        onMain(completion)
    }
}

extension ScannerSessionImpl: RecognitionResultDelegate {
    /// Delivers a result only while its originating view still scans as owner.
    func onRecognition(result: Barcode, viewId: Int64) {
        onMain { [weak self] in
            guard
                let self = self,
                let viewState = self.views[viewId],
                viewState.isCameraOwner,
                viewState.cameraRequested,
                viewState.configurationApplied,
                viewState.scanRequestedByView
            else {
                return
            }
            self.onScanResult(viewId, result)
        }
    }

    /// Keeps recognition intent active after a transient recognition error.
    func onError(error: Error) {
        // Recognition errors preserve the requested scan state for the next frame.
    }
}

extension ScannerSessionImpl: CameraPreviewDelegate {
    /// Allows focus gestures only from the fully active camera owner.
    func canApplyFocus(viewId: Int64) -> Bool {
        guard let viewState = views[viewId] else { return false }
        return canApplyControls(viewState)
    }

    /// Retains native torch state and reports changes for the active owner.
    func onToggleTorch(value: Bool, viewId: Int64) {
        onMain { [weak self] in
            guard
                let self = self,
                let viewState = self.views[viewId],
                self.canApplyControls(viewState)
            else {
                return
            }
            viewState.torchEnabled = value
            self.onTorchChanged(viewId, value)
        }
    }
}
