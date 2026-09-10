/// Object recognition modes supported by the native plugin.
enum RecognitionType {
  /// Barcode recognition.
  barcodeRecognition
}

/// Converts a [RecognitionType] to its native platform value.
extension RecognitionTypeValue on RecognitionType {
  /// Returns the platform-channel value of this recognition type.
  int get rawValue => RecognitionType.values.indexOf(this);
}
