import 'package:mlkit_scanner/mlkit_scanner.dart';

class IosAvailableCameras {
  final Map<IosCameraPosition, Set<IosCameraType>> cameras;

  const IosAvailableCameras({required this.cameras});
}
