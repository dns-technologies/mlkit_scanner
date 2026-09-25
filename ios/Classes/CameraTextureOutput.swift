import Flutter
import CoreVideo
import Foundation

/// Pixel storage shared by the scanner session and Flutter's raster thread.
final class CameraTextureOutput: NSObject, FlutterTexture, CameraPreviewOutput {
    /// Flutter texture registry belonging to this engine.
    private let registry: FlutterTextureRegistry
    /// Protects pixel-buffer storage and disposal across main and raster threads.
    private let lock = NSLock()
    /// Latest retained preview buffer; protected by lock.
    private var pixelBuffer: CVPixelBuffer?
    /// Whether texture storage has been disposed; protected by lock.
    private var closed = false
    /// Registered Flutter texture identifier, assigned during initialization.
    private(set) var textureId: Int64 = -1
    /// Latest preview metadata published on main.
    private var lastDescription: [String: Any]?
    /// Main-thread callback for preview state changes and disposal.
    var publish: ([String: Any]?) -> Void = { _ in }

    /// Registers texture storage with the owning Flutter engine.
    init(registry: FlutterTextureRegistry) {
        self.registry = registry
        super.init()
        textureId = registry.register(self)
    }

    /// Returns a retained snapshot for Flutter rasterization under the storage lock.
    func copyPixelBuffer() -> Unmanaged<CVPixelBuffer>? {
        lock.lock()
        defer { lock.unlock() }
        guard !closed, let buffer = pixelBuffer else { return nil }
        return Unmanaged.passRetained(buffer)
    }

    /// Called on main after the originating receiver has checked its lifetime.
    func present(_ buffer: CVPixelBuffer) {
        lock.lock()
        guard !closed else {
            lock.unlock()
            return
        }
        pixelBuffer = buffer
        lock.unlock()
        registry.textureFrameAvailable(textureId)
        let width = CVPixelBufferGetWidth(buffer)
        let height = CVPixelBufferGetHeight(buffer)
        if lastDescription?["width"] as? Int == width,
           lastDescription?["height"] as? Int == height,
           lastDescription?["state"] as? String == "streaming" { return }
        let description: [String: Any] = ["textureId": textureId, "width": width, "height": height,
            "rotationDegrees": 0, "mirrored": false, "state": "streaming"]
        lastDescription = description
        publish(description)
    }

    /// Publishes a paused state while keeping the latest preview frame.
    func pause() {
        guard var description = lastDescription else { return }
        description["state"] = "paused"
        lastDescription = description
        publish(description)
    }

    /// Unregisters the texture once and clears its retained frame.
    func dispose() {
        lock.lock()
        guard !closed else {
            lock.unlock()
            return
        }
        closed = true
        pixelBuffer = nil
        lock.unlock()
        registry.unregisterTexture(textureId)
        lastDescription = nil
        publish(nil)
    }
}
