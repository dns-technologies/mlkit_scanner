import CoreVideo

/// Receives preview frames and owns their presentation resources.
protocol CameraPreviewOutput: AnyObject {
    /// Reports current output metadata, or nil when output is withdrawn.
    var publish: ([String: Any]?) -> Void { get set }

    /// Presents the latest frame on the main thread.
    func present(_ buffer: CVPixelBuffer)

    /// Marks output paused while retaining its latest frame.
    func pause()

    /// Releases presentation resources and withdraws the output.
    func dispose()
}
