## Unreleased

- Reveal live shared preview during capture handoff and settings updates; cover missing, starting or stopped output to hide stale textures. Keep existing paused snapshots visible without configurable placeholders.
- Preserve the pending hardware-stop barrier when a waiting capture is cancelled, so a replacement cannot start before the previous camera stops.
- Keep zoom/crop range validation in Dart and native argument decoding in shared typed readers, including checked Float conversion on Android.
- Batch changed zoom, flash and crop settings into one `updateCameraSettings` platform call, preserving capture ownership and paused preview retention.
- Apply camera controls during manual pause while retaining the frozen preview; resume recognition only when `scanning` is enabled.
- Simplify the internal controller to widget snapshots and callbacks, remove redundant resource wrappers and unused iOS image helpers, and group tests by the class they exercise. Preserve native Command implementations.
- Preserve each paused widget's camera image across scanner navigation; await pending image copies before camera handoff and release retained images on resume/disposal.

- **Breaking:** Make `BarcodeScanner` declarative with `zoomRatio`, `flashEnabled`, `cropRect`, `camera`, `cameraPaused`, `scanning` and `scanDelay`. Update properties through Flutter state instead of controller commands.
- **Breaking:** Remove the public `BarcodeScannerController`, `initial*` properties, `onScannerInitialized` and `onCameraInitializeError`. Use `onError(Object)` for initialization, capture and control failures, preserving typed `CameraControlException` details.
- Keep recognition disabled by default (`scanning: false`); interpret `scanDelay` as milliseconds and nullable crop/camera properties as resets to their defaults.
- Deliver errors outside build, suppress delivery after disposal, and demonstrate retrying one scanner with a new key in the example.
- Keep the camera session alive beneath dropdowns and dialogs while suspending recognition; transfer ownership when a modal contains another scanner.
- Make manual `cameraPaused` a warm pause: freeze preview and stop native recognition without releasing capture or resetting camera controls. Continue physically stopping hardware when the widget is hidden or the app enters the background.
- Return camera ownership to paused scanner routes before closing the departing scanner, preserving the running camera until manual resume.
- Replace per-widget native Platform Views with a shared Flutter camera texture.
- Keep widget configuration independent and scope commands/results to native capture leases and subscriptions.
- Reconcile the latest desired settings instead of accumulating command closures.
- Release scanner resources after a configurable grace period without an active capture (`MLKitUtils.cameraShutdownDelay`, 300 ms by default). Keep streaming without recognition until a new capture arrives or the deadline expires; hidden registrations do not extend it.
- Save each outgoing widget's own preview for cold return, while new widgets show only live output or a black startup cover.
- Render crop/focus overlays in Flutter.
- Require Flutter 3.29, Dart 3.7 and iOS 12.0 or newer.

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
