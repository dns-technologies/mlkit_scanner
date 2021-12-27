#import "MlkitScannerPlugin.h"
#if __has_include(<mlkit_scanner/mlkit_scanner-Swift.h>)
#import <mlkit_scanner/mlkit_scanner-Swift.h>
#else
// Support project import fallback if the generated compatibility header
// is not copied when this plugin is created as a library.
// https://forums.swift.org/t/swift-static-libraries-dont-copy-generated-objective-c-header/19816
#import "mlkit_scanner-Swift.h"
#endif

@implementation MlkitScannerPlugin
+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar>*)registrar {
  [SwiftMlkitScannerPlugin registerWithRegistrar:registrar];
}
@end
