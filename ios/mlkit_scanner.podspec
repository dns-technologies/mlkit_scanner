#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint mlkit_scanner.podspec' to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'mlkit_scanner'
  s.version          = '0.1.0'
  s.summary          = 'A Flutter plugin to detect barcodes, text, faces, and objects using Google MLKit API'
  s.description      = <<-DESC
A Flutter plugin to detect barcodes, text, faces, and objects using Google MLKit API
                       DESC
  s.homepage         = 'https://www.dns-tech.ru/'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'ООО "ДНС Технологии"' => 'https://www.dns-tech.ru/' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.static_framework = true
  s.dependency 'Flutter'
  s.dependency 'GoogleMLKit/BarcodeScanning', '~> 7.0.0'
  s.platform = :ios, '15.5.0'
  s.resource_bundles = { 'Assets' => ['Assets/*.xcassets'] }


  # Flutter.framework does not contain a i386 slice. Only x86_64 simulators are supported.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'VALID_ARCHS[sdk=iphonesimulator*]' => 'x86_64' }
  s.swift_version = '5.0'
end
