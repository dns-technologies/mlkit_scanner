/// Texture identity, display transforms and frame availability.
struct CameraPreviewDescription: Equatable {
    /// Availability of frames from this preview output.
    enum State: Equatable {
        /// The output is receiving live camera frames.
        case streaming
        /// The output retains its last frame while the camera is stopped.
        case paused
    }

    /// Texture registered for this preview output.
    let textureId: Int64
    /// Width of the delivered pixel buffer.
    let width: Int
    /// Height of the delivered pixel buffer.
    let height: Int
    /// Clockwise rotation needed to display the delivered buffer upright.
    let rotationDegrees: Int
    /// Whether presentation should mirror the delivered buffer horizontally.
    let mirrored: Bool
    /// Current availability of preview frames.
    var state: State

    init(textureId: Int64, width: Int, height: Int, state: State, rotationDegrees: Int = 0, mirrored: Bool = false) {
        self.textureId = textureId
        self.width = width
        self.height = height
        self.rotationDegrees = rotationDegrees
        self.mirrored = mirrored
        self.state = state
    }
}
