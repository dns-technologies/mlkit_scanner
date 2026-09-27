# Test organization

These rules apply to Dart, Android and iOS tests throughout the plugin. They
take precedence over generic advice to split a large test file by scenario.

## One test file per source file

Name the test file after the source file it tests, using the platform's existing
test suffix:

| Source | Test file |
| --- | --- |
| `scanner_preview.dart` | `scanner_preview_test.dart` |
| `Scanner.kt` | `ScannerTest.kt` |
| `ScannerHardware.swift` | `ScannerHardwareTests.swift` |

The source filename determines the test filename, even when the primary class
has a different name. Mirror the source directory structure where practical.

Keep tests for every entity declared in that source file in this same test file:
classes, enums, extensions and top-level functions. For example, tests for
`Scanner` and `ScanResultSubscription`, both declared in `Scanner.kt`, belong in
`ScannerTest.kt`. Do not create separate race, lifecycle or helper-entity test
files for the same source file.

Divide the file into groups by entity and then by meaningful behavior, such as
configuration, lifetime, cancellation or concurrent operations. Do not combine
independent scenarios into one test just to reduce file count.

Private entities may be exercised through the containing component's behavior;
do not expose internals solely to reference them in a group name. A test may
use collaborators from other source files. Assign it to the source whose
behavior it verifies, not to every type mentioned by its setup.

Shared fixtures and test data builders are support files, not extra test suites.
Keep them in the existing support locations and do not put independent tests in
them to bypass the one-file rule.

## One outer group per test file

All tests in a file belong to one outer group identifying its primary tested
type. Use a reference to the actual type in the group name when the test
framework supports it; avoid copying the type name into a string literal.

In Dart, use `group('$MyClass', ...)`, for example:

```dart
void main() {
  group('$ScannerPreviewDescription', () {
    group('decoding', () {
      test('rejects a crop outside the texture', () {
        // Arrange, act and assert this scenario.
      });
    });

    group('$ScannerPreviewStatus', () {
      // Status scenarios from the same source file.
    });
  });
}
```

Put setup and teardown in the narrowest applicable group. Framework binding
initialization may remain outside the group; test registrations may not.

Use the grouping facilities of the existing native test framework:

- **Kotlin / JUnit 4:** a single `MyClassTest` class is the outer group. When
  several independent test classes are needed, nest them under one
  `MyClassTest` suite using `@RunWith(Enclosed::class)`. Kotlin nested classes
  must not be `inner`; keep any Robolectric runner on its relevant nested class.
- **Swift / XCTest:** a single `MyClassTests: XCTestCase` is the outer group.
  Keep related scenarios together, using descriptive method names and `MARK`
  sections for entities or behavior. These sections organize the source;
  XCTest reports the enclosing test case as the suite.

JUnit 4 and XCTest use test-class identifiers for this standard grouping, so
write the type name in that identifier rather than inventing a string-based
grouping API or changing frameworks. String interpolation existing in a language
does not imply that its test runner accepts dynamic suite names.

For source files containing only extensions or top-level functions, use the
extension name or source-file stem when there is no suitable referencable type.
The test filename still follows the source filename.

References: [JUnit 4 Enclosed runner](https://github.com/junit-team/junit4/wiki/Test-runners),
[XCTest test cases and methods](https://developer.apple.com/documentation/xctest/defining-test-cases-and-test-methods).

## Review and verification

Before adding or moving a test:

1. Locate the declaration of the entity under test and its existing test file.
2. Check the filename, outer group and appropriate entity/behavior subgroup.
3. Check that scenarios for other entities in the same source file remain in
   that test file rather than being split into new files.
4. Preserve assertions, setup/teardown scope, runner annotations and isolation
   when regrouping. Verify that the runner discovers every moved test exactly
   once; compare test counts before and after.
5. Run the affected platform tests and lint checks. Report any platform that
   could not be executed rather than treating source inspection as a test run.

This is a test-organization rule, not a requirement to add superficial tests for
every private field or method. Coverage must exercise observable behavior,
including relevant failure and cancellation paths.
