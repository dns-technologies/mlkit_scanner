import 'package:mlkit_scanner/mlkit_scanner.dart';

/// Parameters for initializing the scanner on unsupported platform.
///
///
class EmptyInitialArguments extends InitialArguments {
  const EmptyInitialArguments() : super();

  @override
  Map<String, dynamic> toJson() => {};
}
