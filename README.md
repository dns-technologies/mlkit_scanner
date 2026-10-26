# MLKit Scanner

A Flutter barcode scanner for Android and iOS, using Google ML Kit with CameraX
on Android and AVFoundation on iOS. Scanner widgets share one camera texture per
Flutter engine and retain independent settings.

This package exposes barcode recognition. It does not expose text, face or object
recognition, or support web and desktop platforms.

## Features

- Camera preview with Flutter crop and focus overlays.
- Barcode recognition within a configurable area.
- Declarative zoom, torch, pause and recognition controls.
- Tap to focus and long press to lock focus.
- iOS camera discovery and selection.

## Installation

Requires Flutter 3.29 / Dart 3.7 or newer. Add `mlkit_scanner` to your
`pubspec.yaml` dependencies.

For migration from 0.6.0, see [Migration from the controller API](#migration-from-the-controller-api).

### iOS

Requires iOS 12.0 or newer. Add a camera usage description to
`ios/Runner/Info.plist`:

```xml
<key>NSCameraUsageDescription</key>
<string>Scan barcodes with the camera.</string>
```

Set your application's iOS deployment target to at least 12.0 as well.
The scanner requests camera permission when it starts. If permission was denied,
the user must grant it in system settings before retrying.

### Android

Set the minimum Android SDK version to 21 or higher in
`android/app/build.gradle`:

```groovy
minSdkVersion 21
```

The plugin compiles against Android SDK 34 and targets Java 17. Use a compatible
Android build toolchain. Its manifest declares the camera permission, and the
plugin requests runtime permission when capture starts.

## Declarative usage

Keep desired settings in your Flutter state and pass them to `BarcodeScanner`.
Rebuilding the same widget updates its configuration; no initialization callback
or public scanner controller is needed. `onScan` is required.

This complete example starts recognition explicitly with `scanning: true`, shows
errors, and recreates only the failed scanner when the user retries:

```dart
import 'package:flutter/material.dart';
import 'package:mlkit_scanner/mlkit_scanner.dart';

/// Starts the scanner sample.
void main() => runApp(const MaterialApp(home: ScannerPage()));

/// Demonstrates desired-state scanner controls.
class ScannerPage extends StatefulWidget {
  /// Creates the sample page.
  const ScannerPage({super.key});

  /// Owns desired settings and the most recent result.
  @override
  State<ScannerPage> createState() => _ScannerPageState();
}

/// Retains scanner settings across ordinary rebuilds.
class _ScannerPageState extends State<ScannerPage> {
  /// Whether preview and recognition are paused.
  bool _paused = false;

  /// Whether the user requests barcode recognition.
  bool _scanning = true;

  /// Latest recognized barcode text.
  String _value = 'Scan a barcode';

  /// Original error, including structured camera failures.
  Object? _error;

  /// Session identity, changed only for explicit retry.
  int _sessionId = 0;

  /// Receives errors outside build, so updating UI state is safe.
  void _onError(Object error) => setState(() => _error = error);

  /// Recreates this scanner without replacing the surrounding page.
  void _retry() => setState(() {
        _error = null;
        _sessionId++;
      });

  /// Builds the preview and state-driven controls.
  @override
  Widget build(BuildContext context) {
    final error = _error;
    final message = error is ScannerException
        ? error.message ?? 'Scanner failed'
        : error?.toString();
    return Scaffold(
      appBar: AppBar(title: const Text('Barcode scanner')),
      body: Column(
        children: [
          SizedBox(
            height: 240,
            child: BarcodeScanner(
              sessionId: _sessionId,
              zoomRatio: 1,
              flashEnabled: false,
              cropRect: const CropRect(scaleWidth: .8, scaleHeight: .5),
              cameraPaused: _paused,
              scanning: _scanning,
              scanDelay: 100,
              onScan: (barcode) => setState(() => _value = barcode.rawValue),
              onError: _onError,
            ),
          ),
          Text(_value),
          if (message != null) ...[
            Text(message),
            TextButton(onPressed: _retry, child: const Text('Retry scanner')),
          ],
          TextButton(
            onPressed: () => setState(() => _paused = !_paused),
            child: Text(_paused ? 'Resume camera' : 'Pause camera'),
          ),
          TextButton(
            onPressed: () => setState(() => _scanning = !_scanning),
            child: Text(_scanning ? 'Stop scanning' : 'Start scanning'),
          ),
        ],
      ),
    );
  }
}
```

Give the preview bounded dimensions, for example with `SizedBox` or `Expanded`
inside a bounded `Column`. Crop and focus overlays are rendered by the scanner;
there is no `cropOverlay` constructor argument. To add your own visual content,
place the scanner in a Flutter `Stack` with an `IgnorePointer` overlay.

### Settings and defaults

| Property | Default | Behavior |
| --- | --- | --- |
| `zoomRatio` | `1.0` | Absolute magnification; supported ratios depend on the camera. |
| `flashEnabled` | `false` | Desired torch state. |
| `cropRect` | `null` | Full visible preview; passing `null` again resets the crop. |
| `camera` | `null` | Default camera. iOS only; passing `null` again restores the default. |
| `cameraPaused` | `false` | Freezes preview and stops recognition; camera resources expire after the shutdown grace period. |
| `scanning` | `false` | Enables barcode recognition when the scanner can capture. |
| `scanDelay` | `0` | Milliseconds of cooldown after a successful recognition. |
| `sessionId` | `null` | Session identity compared with `==`; changing it recreates the session with current settings. |

A valid `zoomRatio` is finite and greater than zero. The native camera also
checks its supported minimum and maximum; an unsupported ratio produces an error
instead of being clamped. The public API does not expose zoom-range discovery.
Enabling the torch on a camera without one also produces an error.

`scanDelay` must be an integer from `0` to `2147483647`. Crop scales must be finite
and positive; offsets must be finite. Invalid configuration is reported through
`onError` without replacing the last accepted settings. Correct the properties
and rebuild to apply a valid configuration. Passing a non-null `camera` on Android
reports `UnsupportedError`; Android uses the default back camera.

A scanner displays its preview without recognizing barcodes until `scanning` is
`true`. Set `scanning: false` to stop recognition while keeping the preview active.
A manual pause retains the image and settings. Resuming before the shutdown
deadline reuses the camera; resuming later initializes it again. Camera controls
apply immediately while the paused capture remains warm. After expiry, settings
are retained for resume and focus is unavailable. Recognition requires both `scanning: true` and
`cameraPaused: false`. Changes to `scanDelay` are retained until recognition resumes.
`scanDelay: 0` removes the successful-result cooldown. Both platforms wait
**1000 ms after an attempt without a barcode**, measured from completion.
There is no initial recognition cooldown. Analysis duration and frame availability
also affect the interval between results, so `scanDelay` is not a fixed scan rate.
Changing it affects future successful-result cooldowns, not a deadline already scheduled.

Zoom, flash and crop changes from the same widget update share one native
`updateCameraSettings` call containing only changed fields. The reply waits for
all requested controls. Hardware changes are sequential, not an atomic transaction;
a control failure is delivered through `onError` and the next settings update
reapplies the full current configuration.

Discover iOS cameras with `await MLKitUtils.getIosAvailableCameras()` and pass a
returned `IosCamera` to `camera`. Discovery does not require a scanner controller.
Call it only on iOS and handle its future's errors independently. The single-screen
[example](example/lib/main.dart) demonstrates discovery, null resets, zoom, crop,
torch, recognition delay and per-scanner retry.

`IosCamera` selects by `IosCameraType` and `IosCameraPosition`, not by a device ID.
Selection depends on available hardware. Changing the camera may restart capture;
choose zoom and torch settings supported by the new camera.

`onChangeFlashState` reports actual torch changes on iOS. Keep observed torch state
separate from `flashEnabled` when the UI needs to distinguish desired and actual
state. A black cover hides missing, starting or stopped camera output until the
native stream is live. A live shared texture appears immediately during capture
handoff, without waiting for settings or recognition; zoom changes may be visible.

### Recognition area and focus

`CropRect` uses normalized coordinates relative to the visible preview:

| Field | Default | Meaning |
| --- | --- | --- |
| `scaleWidth` | `1` | Recognition width divided by preview width. |
| `scaleHeight` | `1` | Recognition height divided by preview height. |
| `offsetX` | `0` | Horizontal center displacement divided by half the preview width. |
| `offsetY` | `0` | Vertical center displacement divided by half the preview height. |

For example, `CropRect(scaleWidth: .8, scaleHeight: .5, offsetX: .2)` selects an
area 80% as wide and 50% as tall as the preview. Its center is at 60% of the preview
width and 50% of its height. Rectangles may extend outside the preview; recognition
uses their intersection with the visible frame. An empty intersection produces no result.

The preview uses centered `BoxFit.cover`, including rotation and front-camera
mirroring. Tap anywhere on the scanner to request continuous autofocus at the
**crop center**, or long press to request locked focus there. The touch position
does not select the focus point. Focus depends on camera capabilities and is
available only to the current camera owner after startup, including during a warm
manual pause. It is unavailable after the shutdown deadline.

Focus feedback looks the same on Android and iOS: the focus circle pulses and
fades away, while a long press reveals a lock that moves to the upper-left corner
over 800 ms. Tapping restores autofocus and fades out the lock. With reduced
motion enabled, the lock appears or disappears immediately.

### Barcode results

`onScan` receives one `Barcode` per successful recognition attempt, rather than a
list of every visible barcode. It contains `rawValue` (a non-null string), nullable
`displayValue`, `format` and `valueType`. Results without a raw value are not delivered.
The same barcode can be delivered repeatedly; set `scanning: false` after accepting
a result or deduplicate values in your application.

Supported formats are Code 128, Code 39, Code 93, Codabar, Data Matrix, EAN-13,
EAN-8, ITF, QR Code, UPC-A, UPC-E, PDF417 and Aztec. The public API does not accept
a format filter. `BarcodeFormat.unknown` and `BarcodeValueType.unknown` represent
unrecognized native classification codes.

### Error handling

`onError` is an optional `ValueChanged<Object>`. It receives configuration,
initialization, capture, control and preview-image retention failures with their
original runtime types. These include `ArgumentError`, `RangeError`,
`UnsupportedError` and the typed native failures below.
Do not narrow the callback argument to `PlatformException` or stringify errors
before storing them.

Every native command failure is a `ScannerException`, which extends
`PlatformException` so existing catches continue to work. Both platforms use
the same codes and Dart types:

| Native code | Dart exception | Meaning |
| --- | --- | --- |
| `1` | `CameraInitializationException` | Camera initialization failed. |
| `2` | `CameraPermissionException` | Camera permission denied. |
| `3` | `CameraNotInitializedException` | Camera is not initialized. |
| `4` | `FlashUnavailableException` | Camera has no usable flash. |
| `5` | `InvalidArgumentsException` | Missing or invalid native arguments. |
| `6` | `ZoomUnavailableException` | Camera does not support zoom. |
| `7` or unrecognized | `UnknownScannerException` | Unexpected native failure. |
| `8` | `CameraSessionDisposedException` | Camera session was disposed. |
| `9` | `CameraException` | Camera operation failed. |

Match exception types instead of messages. `UnknownScannerException` retains the
original code, including codes introduced by a newer native implementation.
All types expose `message` and the native `stacktrace` directly. Camera messages
include the failed operation and the underlying SDK description when available.
Native class names and camera-state codes are not exposed as separate fields.
On iOS, the stack records where the failure was captured or reported.
Without `onError`, errors are reported through `FlutterError.reportError`.
Asynchronous disposal failures also use Flutter's error handler.

Recognition attempts that find no barcode and native per-frame recognition errors
do not invoke `onError`; they use the unsuccessful-attempt cooldown.

Error callbacks are delivered outside build and are suppressed after their
session is disposed. They may safely call `setState`, remove the scanner, or
change its `sessionId`. Keep `sessionId` stable for ordinary configuration changes;
change it for an explicit retry after an error. Errors from an old disposed
session cannot affect its replacement.

An unchanged rebuild does not automatically retry failed startup. Change
`sessionId` for an explicit retry. Fix invalid properties before retrying;
recreating the widget cannot make unsupported hardware settings valid.

### Migration from the controller API

Replace `initialArguments` and controller commands with widget properties backed
by application state. `BarcodeScannerController` is internal and is no longer
exported or exposed by `BarcodeScanner`. Use `setState` or your existing state
management solution to change settings.

When upgrading from 0.6.0 to 0.7.0, replace the previous API as follows:

| Previous API in 0.6.0 | Replacement in 0.7.0 |
| --- | --- |
| `initialArguments: AndroidScannerParameters(...)` | Pass `zoomRatio` and `cropRect` directly. |
| `initialArguments: IosScannerParameters(...)` | Pass `zoomRatio`, `cropRect` and `camera` directly. |
| `ScannerParameters.cropRect` / `setCropArea(rect)` | `cropRect: rect`; `null` restores the full visible preview. |
| `AndroidScannerParameters.zoom`, `IosScannerParameters.zoom` / `setZoom(value)` | `zoomRatio: ratio`; convert the zoom scale as described below. |
| `IosScannerParameters.camera` / `setIosCamera(position: ..., type: ...)` | `camera: IosCamera(position: ..., type: ...)`; `null` selects the default. |
| `toggleFlash()` | Toggle your boolean state and pass `flashEnabled: enabled`. |
| `pauseCamera()` / `resumeCamera()` | Set `cameraPaused` to `true` / `false`. |
| `startScan(delay)` | Set `scanning: true` and `scanDelay: delay`. |
| `cancelScan()` | Set `scanning: false`. |
| `setDelay(milliseconds)` | Update `scanDelay`. |
| `onScannerInitialized(controller)` | Remove it; provide desired settings from the first build. |
| `onCameraInitializeError` | Use `onError: (Object error) { ... }`. |

The public `BarcodeScannerController`, `BarcodeScannerInitializeCallback`,
`ScannerParameters`, `AndroidScannerParameters` and `IosScannerParameters` are
removed. The `CameraInitilizeError` typedef from `widgets/camera_preview.dart` is
also removed. Remove imports of the deleted parameter files and use
`package:mlkit_scanner/mlkit_scanner.dart` for the public scanner API.

There is no public initialization-completion callback or command future;
an error callback reports failures rather than readiness. Rebuild with the latest
desired state. Updates made while an operation is pending are coalesced, so
intermediate values may never reach the hardware. Recognition remains disabled
until you request it with `scanning: true`.

The old `setZoom(value)` and initialization `zoom` used normalized values from `0`
to `1`. `zoomRatio` now expresses absolute magnification. Do not copy normalized
values directly: `0` is invalid, and `0.5` requests a ratio of `0.5`, not halfway
through the zoom range. Start with `1.0` or choose supported absolute ratios.
On iOS, the old implementation used
`min(1 + value * 5, camera.maxAvailableVideoZoomFactor)`. Android followed the
previous backend's scale; there is no universal conversion for both platforms.

Review [Settings and defaults](#settings-and-defaults) for validation and timing
changes. `scanDelay` no longer imposes an initial delay or controls attempts without
a barcode. Use `scanning: false` to postpone recognition. Use
[Error handling](#error-handling) to migrate typed callbacks and startup retry.
Manual pause can now release camera resources; see
[Navigation and resource lifetime](#navigation-and-resource-lifetime) before
relying on a camera staying warm.

If you imported `widgets/camera_preview.dart` directly, its previous standalone
camera lifecycle is removed. `CameraPreview` now renders supplied texture metadata;
it does not initialize or own the camera. Use
`BarcodeScanner(scanning: false, onScan: ...)` for a managed preview without recognition.

Direct integrations with `platform/ml_kit_channel.dart` also require changes.
`MlKitChannel` is now final, and capture, event and preview contracts use ownership
handles and subscriptions. Prefer the exported widget and `MLKitUtils` APIs.
`MLKitUtils.getIosAvailableCameras()` remains available without a controller;
call it only on iOS and handle errors through its returned future.

## Navigation and resource lifetime

Each widget retains its own zoom, torch, crop, camera and recognition settings.
Visible scanners share a camera stream and texture; the selected scanner supplies
current settings and receives results. Switching routes reuses that output,
and each screen can show the live stream while its own capture is still being configured.
Only one scanner owns camera controls and recognition at a time. Multiple visible
widgets do not provide independent camera streams or simultaneous recognition.

Opening a dropdown, dialog or modal bottom sheet above the scanner suspends
recognition while keeping its camera session and live preview. Closing the modal
resumes recognition with the latest settings. If the modal contains a scanner,
that scanner takes ownership; closing it returns ownership to the page scanner.
An opaque page hiding the scanner releases its capture and starts the grace period.
Backgrounding the app stops camera work immediately.

Automatic modal tracking follows the scanner's nearest `Navigator`. With nested
navigators, open the modal on that navigator (`useRootNavigator: false`), or
control `scanning` explicitly while a modal on an outer navigator is displayed.

Updates made while an operation is pending are coalesced, and the latest settings
are applied after it completes. Native capture leases and scan subscriptions
prevent late results from a previous capture reaching a new owner.

The Flutter runtime releases camera, analyzer and texture resources after **300 ms
without an unpaused capture** by default. During this grace period, recognition is
disabled but the camera continues streaming into the shared texture. A new capture
cancels shutdown if it is unpaused; registering a hidden widget does not. Captures arriving during
disposal wait for it to finish and acquire fresh resources. There is no native idle timer.

Configure the app-wide grace period before creating scanner widgets:

```dart
MLKitUtils.cameraShutdownDelay = const Duration(milliseconds: 500);
```

`Duration.zero` disables the grace period; negative values throw `ArgumentError`.
Changes apply to the next scheduled shutdown and do not reschedule a pending timer.

Manual pause retains a separate preview image and starts the same shutdown timer.
Native recognition stops, and late results are rejected. Resume before expiry uses
a new recognition subscription within the same capture. After expiry, the saved
image remains visible until a fresh stream is ready. Pausing during startup also
starts the timer, so a pending activation cannot keep the camera alive indefinitely. A paused
widget can take over another scanner's existing capture demand, including on
return from a second scanner screen, without physically stopping the camera.
It remains frozen and does not recognize barcodes until resumed. Without an
existing capture, a widget created paused waits for resume before starting one.

Each widget saves its own image before releasing capture, including when hidden
behind a page without a scanner. Returning to that widget keeps its image visible
through a cold startup, until a live stream is available. A newly created widget
has no saved image: it immediately shows a warm live stream, or a black cover while
waiting for cold startup. It never uses a stopped shared texture as its snapshot.
If no camera frame has been painted yet, there is no image to retain. Retention
failures are reported through `onError`; a saved image is not guaranteed in that case.
Camera handoff and resource disposal wait for pending image copies. Saved images
are released when live preview resumes or their widget is disposed.

Changing controls while paused does not extend the shutdown deadline. Resuming
before expiry cancels it; pausing again starts a new grace period. Capture and
release remain internal operations, separate from `cameraPaused`.

Cold startup, camera switching, permission dialogs and renderer behavior still
take time. Capture completion does not measure Flutter's first rendered frame.
Measure visual latency and transitions on target devices.

## Contributing

Contributions are welcome.

Follow the [test guidelines](docs/test-guidelines.md): one test file per source
file, with a named outer group and entity/behavior groups inside it.

Follow the [code style guidelines](docs/code-style-guidelines.md) for declaration
order, Effective Dart and the commands for Dart, Kotlin and Swift lint checks.

Follow the [comment guidelines](docs/comment-guidelines.md): comments describe an
entity's own purpose and contract without assuming how unrelated components use it.
