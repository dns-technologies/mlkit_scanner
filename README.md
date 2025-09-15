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

#### 16KB Memory Page Compatibility (Android 14/15)

**Important:** If you're targeting newer Android devices (especially ARMv9-based devices running Android 14/15), you may encounter compatibility issues due to 16KB memory page alignment requirements.

**Symptoms:**
- App fails to install on affected devices
- App crashes immediately on startup
- Google Play Console flags compatibility issues

**Solution:**
This plugin has been updated to use the latest Google ML Kit dependencies that support 16KB page alignment. However, if you encounter issues, you can:

1. **Update Dependencies** (recommended): Ensure you're using the latest version of this plugin
2. **Add NDK Configuration**: In your `android/app/build.gradle`, add:
   ```gradle
   android {
       // ... existing configuration
       
       packagingOptions {
           jniLibs {
               useLegacyPackaging = false
           }
       }
       
       // Optional: Enable 16KB page support explicitly
       defaultConfig {
           // ... existing configuration
           ndk {
               // Ensure compatibility with both 4KB and 16KB page sizes
               abiFilters 'arm64-v8a', 'x86_64'
           }
       }
   }
   ```

3. **Exclude Problematic Libraries** (if needed): If you still encounter issues, you can exclude the problematic native libraries and let the system use the updated versions:
   ```gradle
   dependencies {
       implementation('com.google.mlkit:barcode-scanning:17.3.0') {
           exclude group: 'com.google.android.gms', module: 'play-services-mlkit-barcode-scanning'
       }
       implementation 'com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1'
   }
   ```

For more information, see [Android's 16KB page size documentation](https://developer.android.com/guide/practices/page-sizes).
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

