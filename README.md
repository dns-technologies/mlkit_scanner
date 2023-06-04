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
* Set a preview scale.
* Use camera Zoom.
* Lock autofocus.

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
### Example 

```
import 'package:mlkit_scanner/mlkit_scanner.dart';

...

return SizedBox(
  height: 200.0                                 // CameraPreview needs height constraints, if you use widget 
                                                // in Column use SizedBox or Container with height.
  child: BarcodeScanner(
    cropOverlay: ScannerCropOverlay             // you can use default ScannerOverlay, create custom, or do not 
                                                // use it at all

    onScannerInitialized: _onScannerInitialized // callback with BarcodeScannerController for control camera 
                                                // and detection when camera preview initialize.
    
    onCameraInitializeError: (error) {          // Handling error if camera can't initialize on device.
      // handleError.
    }
    onScan: (barcode) {                         // Calls on success barcode recognition
      // Do anything with the code.
    },
  ),
);

Future<void> _onScannerInitialized(BarcodeScannerController controller) async {
    await controller.startScan(100)             // Detection starts only after this call.
                                                // 100 - delay in milliseconds between detection for decreasing 
                                                // CPU consumption. Detection happens every 100 milliseconds 
                                                // skipping frames during delay. Use 0 to turn off delay.

    await controller.stopScan()                 // You can stop detection.

    await controller.setDelay(200)              // Or set delay while detection is going.

    await controller.toggleFlash()              // Toggle device flash. Can throw an Exception if device 
                                                // doesn't have flash.

    await controller.pauseCamera()              // Pause camera preview, detection also stops.

    await controller.resumeCamera()             // Resume camera preview, detection resumes too if 
                                                // controller.startScan calls before.
                                                
    await controller.setZoom(0.5)               // Set camera zoom. Values must be in range 0...1                            
}
```
## Contributing:

Contributions are welcome.

