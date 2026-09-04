import Foundation
import MLKitBarcodeScanning
import UIKit

/// Owns all iOS scanner platform-view state and one explicit camera binding.
final class ScannerSessionImpl: ScannerSession {
    private var views: [Int64: ScannerViewState] = [:]
    private let onScanResult: (Int64, Barcode) -> Void
    private let onTorchChanged: (Int64, Bool) -> Void
    private let notificationCenter: NotificationCenter
    private var lifecycleObservers: [NSObjectProtocol] = []
    private var hostPaused: Bool
    private var isReleased = false

    /// Creates a scanner session with view-scoped native event callbacks.
    init(
        onScanResult: @escaping (Int64, Barcode) -> Void,
        onTorchChanged: @escaping (Int64, Bool) -> Void,
        notificationCenter: NotificationCenter = .default,
        hostIsActive: @escaping () -> Bool = {
            UIApplication.shared.applicationState == .active
        }
    ) {
        self.onScanResult = onScanResult
        self.onTorchChanged = onTorchChanged
        self.notificationCenter = notificationCenter
        hostPaused = !hostIsActive()
        observeHostLifecycle()
    }

    deinit {
        lifecycleObservers.forEach(notificationCenter.removeObserver)
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
        let token = UUID()
        let view = CameraPreview(
            frame: viewFrame,
            viewId: viewId,
            registrationToken: token
        ) { [weak self] disposedViewId, disposedToken in
            self?.disposeView(viewId: disposedViewId, registrationToken: disposedToken)
        }
        attachView(viewId: viewId, view: view, registration: registration)
        return view
    }

    /// Attaches an existing preview so lifecycle behavior can be tested without hardware.
    func attachView(
        viewId: Int64,
        view: CameraPreviewing,
        registration: ScannerViewRegistration = .empty
    ) {
        precondition(!isReleased, "Cannot add a scanner view to a released session")
        precondition(views[viewId] == nil, "Scanner platform view \(viewId) already exists")
        let state = ScannerViewState(viewId: viewId, view: view, registration: registration)
        views[viewId] = state
        view.cameraPreviewDelegate = self
        if let cropArea = registration.initialCropRect {
            view.setCropArea(cropArea)
        }
    }

    /// Transfers ownership, awaits initialization and first frame, then restores retained state.
    func captureCamera(viewId: Int64, completion: @escaping ScannerSessionCompletion) {
        guard !isReleased, let state = liveViewState(viewId) else {
            complete(completion, error: nil)
            return
        }

        let previousState = capturedViewState()
        let previousView = previousState === state ? nil : previousState?.view

        views.values.forEach { candidate in
            let becomesOwner = candidate === state
            if candidate.isCameraOwner && !becomesOwner {
                cancelPendingActivation(candidate)
                deactivateView(candidate)
            }
            candidate.isCameraOwner = becomesOwner
            if !becomesOwner {
                candidate.cameraTransitionReady = false
            }
        }

        state.cameraTransitionReady = previousView == nil
        if previousState === state {
            state.configurationApplied = false
            applyScanState(state)
        }

        activateView(state, completion: completion)
        previousView?.pauseCamera { [weak self, weak view = state.view] in
            guard let self = self else { return }
            self.onMain {
                guard let view = view, self.isCurrentCapture(state, view: view) else { return }
                state.cameraTransitionReady = true
                if state.cameraInitialized {
                    self.resumeInitializedView(state, view: view)
                }
            }
        }
    }

    /// Releases ownership if it is still held by the referenced view.
    func releaseCamera(viewId: Int64, completion: @escaping () -> Void) {
        guard let state = liveViewState(viewId), state.isCameraOwner else {
            complete(completion)
            return
        }

        state.isCameraOwner = false
        state.cameraTransitionReady = false
        cancelPendingActivation(state)
        deactivateView(state)
        guard let view = state.view else {
            complete(completion)
            return
        }
        view.pauseCamera { [weak self] in
            self?.complete(completion)
        }
    }

    /// Pauses camera intent without deleting the referenced view's state.
    func pauseCamera(viewId: Int64, completion: @escaping () -> Void) {
        guard let state = liveViewState(viewId) else {
            complete(completion)
            return
        }
        state.cameraRequested = false
        state.cameraTransitionReady = false
        cancelPendingActivation(state)
        deactivateView(state)

        guard state.isCameraOwner, let view = state.view else {
            complete(completion)
            return
        }
        view.pauseCamera { [weak self, weak view] in
            guard let self = self else { return }
            self.onMain {
                if let view = view, self.isCurrentCapture(state, view: view) {
                    state.cameraTransitionReady = true
                    if state.cameraRequested, state.cameraInitialized {
                        self.resumeInitializedView(state, view: view)
                    }
                }
                self.complete(completion)
            }
        }
    }

    /// Resumes retained camera and recognition intent for the owning view.
    func resumeCamera(viewId: Int64, completion: @escaping ScannerSessionCompletion) {
        guard let state = liveViewState(viewId) else {
            complete(completion, error: nil)
            return
        }
        state.cameraRequested = true
        guard state.isCameraOwner else {
            complete(completion, error: nil)
            return
        }
        activateView(state, completion: completion)
    }

    /// Toggles retained torch state and applies it only to the active owner.
    func toggleFlash(viewId: Int64) throws {
        guard let state = liveViewState(viewId) else { return }
        let enabled = state.torchEnabled != true
        state.torchEnabled = enabled
        guard canApplyControls(state), let view = state.view else { return }
        do {
            try view.setFlash(enabled)
        } catch MlKitPluginError.deviceHasNotFlash {
            throw MlKitPluginError.deviceHasNotFlash
        } catch {
            throw controlError(.torch, state: state, underlyingError: error)
        }
    }

    /// Starts recognition with immediate first analysis and result-based cooldowns.
    func startScan(viewId: Int64, type: RecognitionType, delay: Int) throws {
        guard let state = liveViewState(viewId) else { return }
        state.recognitionType = type
        state.scanDelay = delay
        state.scanRequestedByView = true
        applyScanState(state)
    }

    /// Stops recognition requested by a view without pausing its camera.
    func cancelScan(viewId: Int64) {
        guard let state = liveViewState(viewId) else { return }
        state.scanRequestedByView = false
        applyScanState(state)
    }

    /// Updates the successful-recognition cooldown retained by a view.
    func updateScanPeriod(viewId: Int64, delay: Int) throws {
        guard let state = liveViewState(viewId) else { return }
        state.scanDelay = delay
        state.recognitionHandler?.setDelay(delay: delay)
    }

    /// Updates the absolute zoom ratio retained by a view.
    func setZoomRatio(viewId: Int64, value: Double) throws {
        guard let state = liveViewState(viewId) else { return }
        state.zoomRatio = value
        guard canApplyControls(state), let view = state.view else { return }
        do {
            try view.setZoomRatio(value)
        } catch {
            throw controlError(.zoom, state: state, underlyingError: error)
        }
    }

    /// Updates recognition geometry immediately on the addressed preview.
    func setCropArea(viewId: Int64, cropRect: CropRect) throws {
        guard let state = liveViewState(viewId) else { return }
        state.cropArea = cropRect
        state.view?.setCropArea(cropRect)
        if canApplyControls(state) {
            state.recognitionHandler?.updateCropRect(cropRect: cropRect)
        }
    }

    /// Updates the camera retained by a view.
    func setCamera(viewId: Int64, camera: CameraData) throws {
        guard let state = liveViewState(viewId) else { return }
        state.camera = camera
        state.configurationApplied = false
        guard canApplyControls(state, requireConfiguration: false), let view = state.view else {
            return
        }
        try view.setCamera(camera)
        try applyRetainedConfiguration(state, applyCameraSelection: false)
        state.configurationApplied = true
        applyScanState(state)
    }

    /// Releases all registered views and scanner resources exactly once.
    func release() {
        guard !isReleased else { return }
        isReleased = true
        lifecycleObservers.forEach(notificationCenter.removeObserver)
        lifecycleObservers.removeAll()

        let retainedStates = Array(views.values)
        views.removeAll()
        retainedStates.forEach { state in
            finishPendingActivation(state, error: MlKitPluginError.cameraSessionDisposed)
            state.recognitionHandler = nil
            state.view?.dispose()
        }
    }

    /// Removes a platform view only when the callback belongs to its current registration.
    func disposeView(viewId: Int64, registrationToken: UUID) {
        onMain { [weak self] in
            guard let self = self,
                  let state = self.views[viewId],
                  state.registrationToken == registrationToken else {
                return
            }
            self.views.removeValue(forKey: viewId)
            state.isCameraOwner = false
            state.cameraTransitionReady = false
            self.finishPendingActivation(state, error: nil)
            self.deactivateView(state)
        }
    }

    /// Applies host foreground state; exposed internally for deterministic lifecycle tests.
    func updateHostPaused(_ paused: Bool) {
        onMain { [weak self] in
            guard let self = self, !self.isReleased, self.hostPaused != paused else { return }
            self.hostPaused = paused
            guard let state = self.capturedViewState(), let view = state.view else { return }
            if paused {
                state.cameraTransitionReady = false
                self.cancelPendingActivation(state)
                self.deactivateView(state)
                view.pauseCamera { [weak self, weak view] in
                    guard let self = self else { return }
                    self.onMain {
                        guard let view = view, self.isCurrentCapture(state, view: view) else { return }
                        state.cameraTransitionReady = true
                        if !self.hostPaused, state.cameraRequested, state.cameraInitialized {
                            self.resumeInitializedView(state, view: view)
                        }
                    }
                }
            } else if state.cameraRequested, state.cameraInitialized {
                self.resumeInitializedView(state, view: view)
            }
        }
    }

    /// Starts or resumes capture while the requested registration remains current.
    private func activateView(
        _ state: ScannerViewState,
        completion: @escaping ScannerSessionCompletion
    ) {
        guard let view = state.view, isCurrentCapture(state, view: view), state.cameraRequested else {
            complete(completion, error: nil)
            return
        }

        state.captureCompletions.append(completion)
        if state.cameraInitialized {
            resumeInitializedView(state, view: view)
            return
        }
        guard !state.cameraInitializing else { return }

        state.cameraInitializing = true
        view.initCamera { [weak self, weak view] error in
            guard let self = self else { return }
            self.onMain {
                state.cameraInitializing = false
                guard let view = view, self.isRegistered(state, view: view) else {
                    self.finishPendingActivation(state, error: nil)
                    return
                }
                if let error = error {
                    guard self.isCurrentCapture(state, view: view) else {
                        self.finishPendingActivation(state, error: nil)
                        return
                    }
                    state.isCameraOwner = false
                    state.cameraTransitionReady = false
                    self.deactivateView(state)
                    self.finishPendingActivation(state, error: error)
                    return
                }
                state.cameraInitialized = true
                self.resumeInitializedView(state, view: view)
            }
        }
    }

    /// Waits for layout, handoff, and host readiness before starting capture.
    private func resumeInitializedView(_ state: ScannerViewState, view: CameraPreviewing) {
        guard !state.cameraResuming else { return }
        guard isCurrentCapture(state, view: view), state.cameraRequested else {
            finishPendingActivation(state, error: nil)
            return
        }
        guard !hostPaused, state.cameraTransitionReady else { return }
        guard view.isLayoutReady else {
            guard !state.cameraWaitingForLayout else { return }
            state.cameraWaitingForLayout = true
            view.whenLayoutReady { [weak self, weak view] in
                guard let self = self else { return }
                self.onMain {
                    state.cameraWaitingForLayout = false
                    guard let view = view, self.isRegistered(state, view: view) else {
                        self.finishPendingActivation(state, error: nil)
                        return
                    }
                    self.resumeInitializedView(state, view: view)
                }
            }
            return
        }

        do {
            try applyRetainedConfiguration(state, applyCameraSelection: true)
        } catch {
            state.isCameraOwner = false
            state.cameraTransitionReady = false
            deactivateView(state)
            finishPendingActivation(state, error: error)
            return
        }

        state.cameraResuming = true
        view.resumeCamera { [weak self, weak view] error in
            guard let self = self else { return }
            self.onMain {
                state.cameraResuming = false
                guard let view = view, self.isRegistered(state, view: view) else {
                    self.finishPendingActivation(state, error: nil)
                    return
                }
                self.finishActivation(state, view: view, error: error)
            }
        }
    }

    /// Validates the first frame before exposing scanning as fully active.
    private func finishActivation(
        _ state: ScannerViewState,
        view: CameraPreviewing,
        error: Error?
    ) {
        guard isCurrentCapture(state, view: view),
              state.cameraRequested,
              state.cameraTransitionReady,
              !hostPaused else {
            view.pauseCamera {}
            finishPendingActivation(state, error: nil)
            return
        }
        if let error = error {
            state.isCameraOwner = false
            state.cameraTransitionReady = false
            deactivateView(state)
            view.pauseCamera {}
            finishPendingActivation(
                state,
                error: controlError(.awaitOpen, state: state, underlyingError: error)
            )
            return
        }

        state.configurationApplied = true
        applyScanState(state)
        finishPendingActivation(state, error: nil)
    }

    /// Applies retained camera selection and controls before capture starts.
    private func applyRetainedConfiguration(
        _ state: ScannerViewState,
        applyCameraSelection: Bool
    ) throws {
        guard canApplyControls(state, requireConfiguration: false), let view = state.view else {
            return
        }

        if applyCameraSelection, let camera = state.camera {
            try view.setCamera(camera)
        }
        view.resetFocus()
        if let zoomRatio = state.zoomRatio {
            do {
                try view.setZoomRatio(zoomRatio)
            } catch {
                throw controlError(.zoom, state: state, underlyingError: error)
            }
        }
        if let torchEnabled = state.torchEnabled {
            do {
                try view.setFlash(torchEnabled)
            } catch MlKitPluginError.deviceHasNotFlash where torchEnabled {
                state.torchEnabled = false
                try? view.setFlash(false)
                onTorchChanged(state.viewId, false)
            } catch {
                throw controlError(.torch, state: state, underlyingError: error)
            }
        }
        if let cropArea = state.cropArea {
            view.setCropArea(cropArea)
        }
    }

    /// Reconciles scan intent with ownership and camera readiness.
    private func applyScanState(_ state: ScannerViewState) {
        let shouldScan = canApplyControls(state) && state.scanRequestedByView
        guard shouldScan, let type = state.recognitionType, let delay = state.scanDelay else {
            state.view?.recognitionHandler = nil
            state.view?.setScanActive(false)
            return
        }

        let handler: RecognitionHandler
        if let currentHandler = state.recognitionHandler, currentHandler.type == type {
            handler = currentHandler
            handler.setDelay(delay: delay)
            if let cropArea = state.cropArea {
                handler.updateCropRect(cropRect: cropArea)
            }
        } else {
            handler = type.createRecognitionHandler(
                delay: delay,
                cropRect: state.cropArea,
                viewId: state.viewId
            )
            handler.delegate = self
            state.recognitionHandler = handler
        }
        state.view?.recognitionHandler = handler
        state.view?.setScanActive(true)
    }

    /// Stops recognition while retaining its requested state for a future activation.
    private func deactivateView(_ state: ScannerViewState) {
        state.configurationApplied = false
        state.view?.recognitionHandler = nil
        state.view?.setScanActive(false)
    }

    /// Completes and removes every caller sharing the current activation.
    private func finishPendingActivation(_ state: ScannerViewState, error: Error?) {
        let completions = state.captureCompletions
        state.captureCompletions.removeAll()
        completions.forEach { complete($0, error: error) }
    }

    /// Treats a superseded activation as a successful cancellation.
    private func cancelPendingActivation(_ state: ScannerViewState) {
        finishPendingActivation(state, error: nil)
    }

    /// Returns a registered view state whose weak preview is still alive.
    private func liveViewState(_ viewId: Int64) -> ScannerViewState? {
        guard !isReleased, let state = views[viewId], state.view != nil else { return nil }
        return state
    }

    /// Returns the one registered view that currently owns the camera.
    private func capturedViewState() -> ScannerViewState? {
        views.values.first { $0.isCameraOwner }
    }

    /// Checks that an asynchronous callback still belongs to this registration.
    private func isRegistered(_ state: ScannerViewState, view: CameraPreviewing) -> Bool {
        !isReleased
            && views[state.viewId] === state
            && state.registrationToken == view.registrationToken
            && state.view === view
    }

    /// Checks ownership again after asynchronous work.
    private func isCurrentCapture(_ state: ScannerViewState, view: CameraPreviewing) -> Bool {
        isRegistered(state, view: view) && state.isCameraOwner
    }

    /// Returns whether retained controls may be applied to this view now.
    private func canApplyControls(
        _ state: ScannerViewState,
        requireConfiguration: Bool = true
    ) -> Bool {
        guard let view = state.view else { return false }
        return isCurrentCapture(state, view: view)
            && !hostPaused
            && state.cameraTransitionReady
            && state.cameraInitialized
            && state.cameraRequested
            && (!requireConfiguration || state.configurationApplied)
    }

    /// Preserves an existing typed camera error or adds missing operation context.
    private func controlError(
        _ operation: CameraControlOperation,
        state: ScannerViewState,
        underlyingError: Error
    ) -> CameraControlError {
        if let error = underlyingError as? CameraControlError {
            return CameraControlError(
                operation: operation,
                viewId: state.viewId,
                underlyingError: error.underlyingError,
                cameraStateErrorCode: error.cameraStateErrorCode
            )
        }
        return CameraControlError(
            operation: operation,
            viewId: state.viewId,
            underlyingError: underlyingError
        )
    }

    /// Observes UIKit foreground transitions and mirrors them into camera intent.
    private func observeHostLifecycle() {
        let pauseObserver = notificationCenter.addObserver(
            forName: UIApplication.willResignActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.updateHostPaused(true)
        }
        let resumeObserver = notificationCenter.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.updateHostPaused(false)
        }
        lifecycleObservers = [pauseObserver, resumeObserver]
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
    private func complete(_ completion: @escaping ScannerSessionCompletion, error: Error?) {
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
            guard let self = self,
                  let state = self.liveViewState(viewId),
                  self.canApplyControls(state),
                  state.scanRequestedByView else {
                return
            }
            self.onScanResult(viewId, result)
        }
    }

    /// Keeps recognition intent active after a transient recognition error.
    func onError(error: Error) {
        // The frame gate applies a retry cooldown and keeps scanning requested.
    }
}

extension ScannerSessionImpl: CameraPreviewDelegate {
    /// Allows focus gestures only from the fully active camera owner.
    func canApplyFocus(viewId: Int64) -> Bool {
        guard let state = liveViewState(viewId) else { return false }
        return canApplyControls(state)
    }

    /// Retains native torch state and reports changes for the active owner.
    func onToggleTorch(value: Bool, viewId: Int64) {
        onMain { [weak self] in
            guard let self = self,
                  let state = self.liveViewState(viewId),
                  self.canApplyControls(state) else {
                return
            }
            state.torchEnabled = value
            self.onTorchChanged(viewId, value)
        }
    }
}
