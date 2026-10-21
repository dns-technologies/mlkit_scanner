## Unreleased

See [Migration from the controller API](README.md#migration-from-the-controller-api) for migration from 0.6.0.

- **Breaking:** Make `BarcodeScanner` declarative with `zoomRatio`, `flashEnabled`, `cropRect`, `camera`, `cameraPaused`, `scanning` and `scanDelay`. Update properties through Flutter state instead of controller commands.
- **Breaking:** Remove the public `BarcodeScannerController`, `BarcodeScannerInitializeCallback`, `initialArguments`, `ScannerParameters`, `AndroidScannerParameters`, `IosScannerParameters`, `onScannerInitialized` and `onCameraInitializeError`. Pass settings directly to the widget and receive failures through `onError(Object)`.
- **Breaking:** Replace normalized linear zoom (`setZoom`, range 0–1) with absolute `zoomRatio` (`1.0` is the default magnification). Supported ratios depend on the selected camera.
- **Breaking:** Apply `scanDelay` in milliseconds after successful recognition on both platforms; attempts without a barcode use a fixed 1-second cooldown. Remove the initial scan delay and frame-count-based sampling.
- **Breaking:** Require Flutter 3.29, Dart 3.7 and iOS 12.0 or newer.
- Replace the Android CameraView backend with CameraX 1.4.2.
- Replace per-widget native Platform Views with one shared Flutter camera texture per engine; render crop and animated focus overlays in Flutter, including reduced-motion support.
- Retain independent settings for each scanner and apply the latest desired state after pending operations. Batch changed zoom, flash and crop settings into one native call.
- Keep recognition disabled by default (`scanning: false`); setting `cropRect` or iOS `camera` to `null` resets it to the default.
- Add `CameraException` with descriptive messages, camera-state and native cause details. Deliver initialization, capture and control errors outside build, suppress stale errors after disposal, and demonstrate retrying a scanner with a new key in the example.
- Validate zoom, crop and scan delay before applying settings; report invalid configuration through `onError` and allow corrected settings on the same widget.
- Keep the camera session alive beneath dropdowns and dialogs while suspending recognition; transfer ownership when a modal contains another scanner.
- Add `MLKitUtils.cameraShutdownDelay` (300 ms by default): release camera, analyzer and texture resources after capture is released or manually paused. Keep streaming without recognition during the grace period; resume cancels shutdown, while hidden registrations and paused control updates do not extend it. Backgrounding the app stops camera work immediately.
- Make `cameraPaused` retain the widget's image and settings. Apply controls while the capture remains warm; after shutdown, retain updates for a fresh startup on resume. Recognition resumes only when `scanning` is enabled.
- Preserve each widget's own preview across pause and navigation, including cold restarts. Await pending image copies before camera handoff or disposal, and release saved images when live preview resumes or the widget is disposed.
- Show live shared preview during capture handoff and settings updates; hide missing, starting or stopped output behind a black cover until streaming begins.
- Fix capture, release and pause/resume races, including cancelled captures waiting for hardware shutdown; reject late scan and torch events from previous owners.
- Fix excessive portrait preview magnification on Android and align crop and focus coordinates with the visible preview, including rotation and front-camera mirroring.
- Preserve Android camera permission requests across Activity recreation and complete pending requests on final detach.
- Reuse Android frame-conversion buffers, skip conversion while analysis is paused or throttled, and defer analyzer cleanup until in-flight recognition finishes without blocking disposal.

## 0.6.0

[fix]

- Update Google ML Kit dependencies to support 16KB page alignment

## 0.5.4

[feature]

- Availability use Scanner with global key.
- Gradle 8 migration

## 0.5.3

[fix]

- Fix iOS scan throttling.

## 0.5.2

[fix]

- Fix Android camera pauses after reinitialization.

## 0.5.1

[fix]

- Update dependency versions.

## 0.5.0

[feature]

- Add scanned barcode info.
- Add ability to initialize the scanner with parameters (zoom, cropRect and camera (Ios)).

[fix]

- Fix lock animation when layout changing.
- Fix cropRect initialization.

## 0.4.0

[feature]

- Add the ability to get available cameras on iOS and choice which one to use.

## 0.3.6

[fix]

- Improve camera selection on devices with multiple cameras (iOS).
- Make controller in initialization callback non-nullable.

## 0.3.5

[fix]

- Fix auto resume camera after manually pause (Android).
- Fix lateinit property center has not been initialized at CenterFocusView.lockMovementAnimation (Android).

## 0.3.4

[fix]

- Fix unit tests
- Migration to using targetPlatform instead of Platform.is
- Fix issues with NativeView disappearing on rebuild or hotreload

## 0.3.3

[fix]

- Fix "smart cast to 'String' is impossible, because 'it.message' is a property that has open or custom getter" in MISingleBarcodeAnalyzer.kt
- Fix camera focus on ios
- Fix example xcode asked to sign scanner assets for each build problem

## 0.3.2

[fix]

- Added null check in tryAnalyzeInputImage

## 0.3.1

[fix]

- Now center of the camera focus view depends on offsets

## 0.3.0

* support Flutter 3.0

## 0.2.0

* Migrate from AndroidView to PlatformViewLink

New Features

* resize CameraPreview widget at runtime
* change crop area at runtime

## 0.1.0

* Initial Release
* MLkit Barcode Scanning Vision API.
