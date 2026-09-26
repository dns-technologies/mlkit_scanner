# MLKit Scanner

A Flutter barcode scanner for Android and iOS, using Google ML Kit with CameraX
on Android and AVFoundation on iOS. Scanner widgets share one camera texture per
Flutter engine and retain independent settings.

## Features

- Camera preview with Flutter crop and focus overlays.
- Barcode recognition within a configurable area.
- Declarative zoom, torch, pause and recognition controls.
- Tap to focus and long press to lock focus.
- iOS camera discovery and selection.

## Installation

Requires Flutter 3.29 / Dart 3.7 or newer. Add `mlkit_scanner` to your
`pubspec.yaml` dependencies.

### iOS

Requires iOS 12.0 or newer. Add a camera usage description to
`ios/Runner/Info.plist`:

```xml
<key>NSCameraUsageDescription</key>
<string>Scan barcodes with the camera.</string>
```

### Android

Set the minimum Android SDK version to 21 or higher in
`android/app/build.gradle`:

```groovy
minSdkVersion 21
```

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
  /// Whether preview and recognition are paused while the camera stays warm.
  bool _paused = false;

  /// Whether the user requests barcode recognition.
  bool _scanning = true;

  /// Latest recognized barcode text.
  String _value = 'Scan a barcode';

  /// Original error, including structured camera failures.
  Object? _error;

  /// Stable widget identity, replaced only for explicit retry.
  Key _scannerKey = UniqueKey();

  /// Receives errors outside build, so updating UI state is safe.
  void _onError(Object error) => setState(() => _error = error);

  /// Recreates this scanner without replacing the surrounding page.
  void _retry() => setState(() {
        _error = null;
        _scannerKey = UniqueKey();
      });

  /// Builds the preview and state-driven controls.
  @override
  Widget build(BuildContext context) {
    final error = _error;
    final message = error is CameraControlException
        ? '${error.operation.wireValue}: ${error.cause ?? error.message}'
        : error?.toString();
    return Scaffold(
      appBar: AppBar(title: const Text('Barcode scanner')),
      body: Column(
        children: [
          SizedBox(
            height: 240,
            child: BarcodeScanner(
              key: _scannerKey,
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
| `cameraPaused` | `false` | Freezes preview and stops recognition while keeping the camera running. |
| `scanning` | `false` | Enables barcode recognition when the scanner can capture. |
| `scanDelay` | `0` | Milliseconds of cooldown after a successful recognition. |

A scanner displays its preview without recognizing barcodes until `scanning` is
`true`. Set `scanning: false` to stop recognition while keeping the preview active.
A manual pause retains the capture and settings; resuming uses the retained
`scanning` value without reinitializing the camera. Camera controls, including
flash, zoom and focus, apply immediately while paused; the frozen image stays
unchanged until resume. Recognition requires both `scanning: true` and
`cameraPaused: false`. Changes to `scanDelay` are retained until recognition resumes.
`scanDelay: 0` removes the successful-result cooldown; failed attempts still
follow native frame sampling and throttling.

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

`onChangeFlashState` reports actual torch changes on iOS. Keep observed torch state
separate from `flashEnabled` when the UI needs to distinguish desired and actual
state. The preview area remains empty until a texture is registered, then renders
that texture immediately without a readiness cover.

### Error handling

`onError` is an optional `ValueChanged<Object>`. It receives scanner initialization,
capture and control failures with their original runtime types, including
`CameraControlException` and its operation/cause details. Do not narrow the
callback argument to `PlatformException` or stringify errors before storing them.

Error callbacks are delivered outside build and are suppressed after their
scanner is disposed. They may safely call `setState`, remove the scanner, or
replace its key. Keep the key stable for ordinary configuration changes; create a
new `UniqueKey` only when you intend to replace that scanner's lifetime, such as an
explicit retry after an error. Errors from an old disposed scanner cannot affect
its replacement.

### Migration from the controller API

`BarcodeScannerController` is internal and is no longer exported or exposed by
`BarcodeScanner`. Replace imperative commands with widget properties backed by
application state:

| Previous API | Declarative replacement |
| --- | --- |
| `initialZoomRatio`, `setZoomRatio(value)` | `zoomRatio: value` |
| `initialFlashEnabled`, `toggleFlash()` | `flashEnabled: enabled`; toggle your state variable |
| `initialCropRect`, `setCropArea(value)` | `cropRect: value`; `null` restores the full preview |
| `initialCamera`, `setIosCamera(...)` | `camera: selectedCamera`; `null` restores the default |
| `pauseCamera()` / `resumeCamera()` | `cameraPaused: true` / `false` |
| `startScan(delay)` | `scanning: true, scanDelay: delay` |
| `cancelScan()` | `scanning: false` |
| `setDelay(milliseconds)` | `scanDelay: milliseconds` |
| `onScannerInitialized` | Remove it; pass desired settings directly |
| `onCameraInitializeError` | `onError: (Object error) { ... }` |

Use `setState` or your existing state-management solution to change these values.
A repeated build with unchanged settings does not require an initialization hook.

## Navigation and resource lifetime

Each widget retains its own zoom, torch, crop, camera and recognition settings.
Visible scanners share a camera stream and texture; the selected scanner supplies
current settings and receives results. Switching routes reuses that output.
During a transition both widgets can display the same camera image.

Opening a dropdown, dialog or modal bottom sheet above the scanner suspends
recognition while keeping its camera session and live preview. Closing the modal
resumes recognition with the latest settings. If the modal contains a scanner,
that scanner takes ownership; closing it returns ownership to the page scanner.
An opaque page hiding the scanner or a background app still pauses camera work.

Automatic modal tracking follows the scanner's nearest `Navigator`. With nested
navigators, open the modal on that navigator (`useRootNavigator: false`), or
control `scanning` explicitly while a modal on an outer navigator is displayed.

Updates made while an operation is pending are coalesced, and the latest settings
are applied after it completes. Native capture leases and scan subscriptions
prevent late results from a previous capture reaching a new owner.

The Flutter runtime releases camera, analyzer and texture resources after **300 ms
with zero registered scanner widgets**. Hidden and manually paused widgets still
count as consumers. A widget arriving during disposal waits for it to finish.
There is no native idle timer.

Manual pause retains a separate preview image while keeping the camera enabled,
including its current zoom and torch state. Native recognition stops, and late results are
rejected. Resume uses a new recognition subscription within the same capture.
Pausing during startup lets that startup finish without recognition. A paused
widget can take over another scanner's existing capture demand, including on
return from a second scanner screen, without physically stopping the camera.
It remains frozen and does not recognize barcodes until resumed. Without an
existing capture, a widget created paused waits for resume before starting one.

Hiding the widget or backgrounding the app still stops hardware. After those
events or a physical camera switch, activation may be needed again. Capture and
release remain internal controller operations, separate from `cameraPaused`.
Each paused widget keeps its own image from the pause, including after another
scanner uses the shared camera or native output is temporarily lost. Camera
handoff waits for a pending image copy before applying the next owner's settings.
The image is released on resume or widget disposal. Only live previews share
the current output and leave the preview area empty after surface loss.

Cold startup, camera switching, permission dialogs and renderer behavior still
take time. Capture completion does not measure Flutter's first rendered frame.
Measure visual latency and transitions on target devices.

Crop and focus coordinates are relative to the widget's centered `BoxFit.cover`
image, including rotation and front-camera mirroring.

## Contributing

Contributions are welcome.
