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
