/// Recognition types for objects
enum RecognitionType {
  /// Barcode recognition
  barcodeRecognition
}

extension RecognitionTypeValue on RecognitionType {
  /// Returns raw value of this [RecognitionType]
  int get rawValue => RecognitionType.values.indexOf(this);
}
