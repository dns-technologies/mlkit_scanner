import AVFoundation
import Foundation

/// The delegate belongs to one actual camera stream; closing it revokes queued frames.
final class CameraFrameReceiver: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    /// Protects mutable state shared across callback queues.
    private let lock = NSLock()
    /// Whether queued preview and analysis work must be discarded; protected by lock.
    private var closed = false
    /// Recognition endpoint snapshotted when admitting a frame; protected by lock.
    private var recognition: RecognitionHandler?
    /// Latest Flutter viewport dimensions; protected by lock.
    private var viewport = CGSize(width: 1, height: 1)
    /// Dimensions of the latest received buffer; protected by lock.
    private var size = CGSize(width: 720, height: 1280)
    /// Latest preview frame awaiting main-thread delivery; protected by lock.
    private var pendingBuffer: CVPixelBuffer?
    /// Whether a main-thread preview delivery is already scheduled; protected by lock.
    private var deliveryPending = false
    /// Serial queue for recognition submission, independent of preview delivery.
    private let analysisQueue = DispatchQueue(label: "mlkit_scanner.recognition", qos: .userInitiated)
    /// Whether recognition submission is already queued or running; protected by lock.
    private var analyzing = false
    /// Main-thread callback installed before attaching this receiver to capture output.
    var onPreview: ((CVPixelBuffer) -> Void)?
    /// Returns the latest buffer dimensions under the state lock.
    var latestSize: CGSize {
        lock.lock()
        defer {
            lock.unlock()
        }
        return size
    }

    /// Replaces the recognition endpoint for future frame submissions.
    func setHandler(_ handler: RecognitionHandler?) {
        lock.lock()
        recognition = handler
        lock.unlock()
    }

    /// Updates the recognition endpoint and viewport atomically.
    func setRecognition(_ handler: RecognitionHandler?, viewport: CGSize) {
        lock.lock()
        recognition = handler
        self.viewport = viewport
        lock.unlock()
    }

    /// Revokes the receiver and drops queued preview data under the state lock.
    func close() {
        lock.lock()
        closed = true
        recognition = nil
        pendingBuffer = nil
        lock.unlock()
    }

    /// Coalesces preview frames and admits at most one recognition submission at a time.
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        lock.lock()
        guard !closed else {
            lock.unlock()
            return
        }
        size = CGSize(width: CVPixelBufferGetWidth(buffer), height: CVPixelBufferGetHeight(buffer))
        pendingBuffer = buffer
        let schedule = !deliveryPending
        deliveryPending = true
        let handler = analyzing ? nil : recognition
        if handler != nil { analyzing = true }
        let viewport = self.viewport
        lock.unlock()
        if schedule {
            DispatchQueue.main.async { [weak self] in self?.deliverPreview() }
        }
        if let handler = handler {
            analysisQueue.async { [weak self] in
                guard let self = self else { return }
                self.lock.lock()
                let live = !self.closed
                self.lock.unlock()
                if live { handler.processVideoOutput(sampleBuffer: sampleBuffer, viewport: viewport) }
                self.lock.lock()
                self.analyzing = false
                self.lock.unlock()
            }
        }
    }

    /// Takes the newest pending frame for main-thread preview delivery.
    private func deliverPreview() {
        lock.lock()
        let buffer = closed ? nil : pendingBuffer
        pendingBuffer = nil
        deliveryPending = false
        lock.unlock()
        if let buffer = buffer { onPreview?(buffer) }
    }
}
