# MLKit Scanner

_A Flutter plugin to detect barcodes, text, faces, and objects using [Google MLKit API](https://developers.google.com/ml-kit/) for iOS and Android_

This Plugin uses Android CameraView library and iOS AVFoundation APIs for detecting objects from device's camera.

*Note*: This plugin is under development, and some APIs might not be available yet. 

## Features:

* Display camera preview in a widget.
* Set size of the camera preview.
* Set overlay for camera preview.
* Set area for detect object.
* Pause/Resume camera preview.
* Toogle device flash.
* Set a preview scale
* Use camera Zoom 
* Lock autofocus

| Google MLKit APIs:             | Android | iOS |
|--------------------------------|---------|-----|
| Barcode scanning               |   ✅    | ✅ |
| ------------------------       |    -    |  -  |

## Installation

First, add `mlkit_scanner` as a dependency in your pubspec.yaml.

### iOS

iOS 11.0 of higher is needed to use the camera plugin.

Add key to the `ios/Runner/Info.plist`:

```xml
<key>NSCameraUsageDescription</key>
<string>...description...</string>
```

### Android

Change the minimum Android sdk version to 21 (or higher) in your `android/app/build.gradle` file.

```
minSdkVersion 21
```

### Navigation and initialization

Each `BarcodeScanner` owns independent retained configuration. Its first camera
capture starts one per-view initialization; a later capture of the same view
waits for that same in-flight work. Covering the scanner with another route does
not cancel initialization. While the scanner is inactive, controller commands
update only its retained state and never reconfigure the active scanner. When
the route becomes active again, initialization is awaited before the latest
retained state is applied.

During a rapid `A → B → A` transition, closing scanner B recaptures the
existing scanner A even if Flutter has not disabled A's `TickerMode` yet. It
does not create another A route. Native commands and events remain scoped to
their platform-view id, so late work from A cannot update B's retained state or
deliver B's events. Focus gestures are detached with their old owner and focus
regions are reset before the next preview is revealed. On iOS, physical
capture-session start, stop, and reconfiguration are serialized across all
scanner previews.

`onScannerInitialized` means that the native platform view is registered and
the controller is safe to use, not that its camera is already active. The
callback is delivered before camera capture completes, so configuration and
scan commands can update retained per-view state during initialization. If an
in-flight capture fails, the controller remains valid and the initialization or
retained-control error is delivered separately to `onCameraInitializeError`.
Errors from controller commands remain errors of the returned `Future`.

### Example 

```
import 'package:mlkit_scanner/mlkit_scanner.dart';

...

return SizedBox(
  height: 200.0                                 // CameraPreview needs height constraints, if you use widget 
                                                // in Column use SizedBox or Container with height.
  child: BarcodeScanner(
    initialZoomRatio: 1.0,                      // Each scanner widget owns and restores
    initialFlashEnabled: false,                 // its own zoom, torch and crop configuration.
    cropOverlay: ScannerCropOverlay             // you can use default ScannerOverlay, create custom, or do not 
                                                // use it at all

    onScannerInitialized: _onScannerInitialized // Called once the native view is registered;
                                                // camera capture may still be pending.
    
    onCameraInitializeError: (error) {          // Handles capture-time initialization/configuration errors.
      // handleError.
    }
    onScan: (barcode) {                         // Calls on success barcode recognition
      // Do anything with the code.
    },
  ),
);

Future<void> _onScannerInitialized(BarcodeScannerController controller) async {
    await controller.startScan(100)             // Detection starts only after this call.
                                                // 100 - delay in milliseconds after successful recognition.
                                                // Failed attempts continue with platform frame sampling.
                                                // skipping frames during delay. Use 0 to turn off delay.

    await controller.stopScan()                 // You can stop detection.

    await controller.setDelay(200)              // Or set delay while detection is going.

    await controller.toggleFlash()              // Toggle device flash. Can throw an Exception if device 
                                                // doesn't have flash.

    await controller.pauseCamera()              // Pause camera preview, detection also stops.

    await controller.resumeCamera()             // Resume camera intent for the active scanner.
                                                // A hidden scanner retains the request until it returns.

    await controller.setZoomRatio(2.0)          // Set absolute camera zoom to 2x.
                                                // Supported ratios depend on the selected camera.
}
```
## Contributing:

Contributions are welcome.
