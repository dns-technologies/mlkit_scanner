import Flutter
import CoreVideo
import XCTest
@testable import mlkit_scanner

final class CameraTextureOutputTests: XCTestCase {
    func testTexturePublishesOnlyMetadataChangesAndRetainsLatestBuffer() throws {
        let registry = RecordingTextureRegistry()
        let texture = CameraTextureOutput(registry: registry)
        var descriptions: [[String: Any]?] = []
        texture.publish = { descriptions.append($0) }
        XCTAssertNil(texture.copyPixelBuffer())
        var buffer: CVPixelBuffer?
        XCTAssertEqual(CVPixelBufferCreate(kCFAllocatorDefault, 16, 8, kCVPixelFormatType_32BGRA, nil, &buffer), kCVReturnSuccess)
        let frame = try XCTUnwrap(buffer)
        texture.present(frame); texture.present(frame)
        XCTAssertEqual(registry.frames, [42, 42])
        XCTAssertEqual(descriptions.count, 1)
        XCTAssertEqual(descriptions[0]?["width"] as? Int, 16)
        XCTAssertTrue(texture.copyPixelBuffer()?.takeRetainedValue() === frame)
        texture.pause()
        XCTAssertEqual(descriptions.compactMap { $0 }.last?["state"] as? String, "paused")
        XCTAssertNotNil(texture.copyPixelBuffer()?.takeRetainedValue())
        texture.dispose(); texture.dispose()
        XCTAssertNil(texture.copyPixelBuffer())
        XCTAssertEqual(registry.removed, [42])
    }
    func testLateFrameCannotRepopulateDisposedTexture() throws {
        let registry = RecordingTextureRegistry()
        let texture = CameraTextureOutput(registry: registry)
        var frame: CVPixelBuffer?
        CVPixelBufferCreate(kCFAllocatorDefault, 16, 8, kCVPixelFormatType_32BGRA, nil, &frame)
        texture.dispose(); texture.present(try XCTUnwrap(frame))
        XCTAssertTrue(registry.frames.isEmpty)
        XCTAssertNil(texture.copyPixelBuffer())
    }
}

final class RecordingTextureRegistry: NSObject, FlutterTextureRegistry {
    var registered: [FlutterTexture] = []
    var frames: [Int64] = []
    var removed: [Int64] = []
    func register(_ texture: FlutterTexture) -> Int64 { registered.append(texture); return 42 }
    func textureFrameAvailable(_ textureId: Int64) { frames.append(textureId) }
    func unregisterTexture(_ textureId: Int64) { removed.append(textureId); registered.removeAll() }
}
