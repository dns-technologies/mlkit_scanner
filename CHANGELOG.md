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
