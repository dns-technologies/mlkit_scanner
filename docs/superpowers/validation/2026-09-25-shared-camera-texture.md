# Shared camera texture validation

Implementation completed in the existing MPK-7752 checkout on 2026-09-25. No commit or publication was made.

The table below records the initial migration checks. Later focus repair and cleanup results are recorded in the final sections.

## Automated checks

| Check | Result |
| --- | --- |
| `flutter test --no-pub` at repository root | 69 passed |
| `flutter analyze --no-pub` at repository root | No issues |
| `example/android/gradlew.bat :mlkit_scanner:testDebugUnitTest --console=plain` | 319 passed, 0 failed, 0 skipped |
| `flutter build apk --debug --no-pub` in `example` | Passed; `example/build/app/outputs/flutter-apk/app-debug.apk` |
| Dart formatter and `git diff --check` | Checked before delivery |
| iOS build / XCTest | Not run: Windows has no Xcode / Apple SDK |
| Device visual latency comparison | Not run; no measured black-frame or first-visible-frame claim |

Flutter and Gradle commands used the installed FVM SDK and Android Studio JBR. Gradle required network access outside the sandbox to use its distribution/dependencies. Initial sandbox-only build failures were environmental; the permitted build succeeded.

## Behavior covered

- Widget registrations own demand; hidden and paused widgets count. Disposal starts at 300 ms with zero widgets, is cancelled by a new registration, and already-started disposal is awaited before recreation.
- Warm scanner navigation retains the texture ID and avoids camera pause during handoff. A new widget selects its lease before waiting for registration.
- Point controls carry capture IDs. Pending changes coalesce to the latest desired state; ownership changes and close bypass unfinished controls.
- A -> B -> A and cancel -> restart reject the old result subscription, including late native events and lease allocations.
- Preview subscription buffering handles events arriving before the initial snapshot reply.
- Cleanup attempts all resources after failures. Android replies to disposal only after all provided SurfaceRequest result callbacks, even when other cleanup fails.
- An output with no frame stays `starting` through pause/closed camera notifications; it cannot reveal an empty Texture as a paused image.
- Android tests retain frame conversion, analyzer, control completion, permission and lifetime coverage. Obsolete native-view/overlay tests were removed with those implementations; Flutter tests now cover rendering and navigation.
- iOS XCTest sources cover texture buffer retention, disposal, logical registration, lease identity, permission cancellation and geometry. They have not been compiled or executed here.

## Independent review

The reviewer examined Flutter ownership/reconciliation, Android surfaces and lifecycle, and iOS concurrency. Supported findings were fixed: disposal/pause barriers, early preview events, source crop, off-center focus, stale surface restoration, pending replies, main-thread blocking during startup cancellation, observer confinement, bounded independent analysis, and surface disposal acknowledgements. No generation/epoch counters were introduced. IDs identify real widget, lease, subscription, texture or camera-frame entities.

## Implementation notes

- Preview uses Flutter Texture and Flutter overlays; Android SurfaceProducer and iOS CVPixelBuffer output do not transport video through MethodChannel.
- iOS uses an object owning the configuration completion and a serial AVFoundation queue. Pixel-buffer access and receiver lifetime snapshots use locks; events are delivered on main.
- Android camera capture completion is a readiness signal, not confirmation of Flutter raster presentation. It is associated with its actual SurfaceRequest and camera frame number.
- `getForcedNewSurface` is absent from the [Flutter 3.29 interface](https://github.com/flutter/flutter/blob/3.29.0/engine/src/flutter/shell/platform/android/io/flutter/view/TextureRegistry.java); the implementation uses its public `getSurface` API. Test surface recovery on supported Android backends, especially older devices.
- Requirements are Flutter >=3.29 / Dart >=3.7, Android API >=21, iOS >=12. CameraX remains 1.4.2; ML Kit versions are unchanged.
- Live scanners use the shared output. Paused scanners now retain independent images (see the paused-frame correction below).

## Remaining platform validation

On macOS, from `example`, install the existing dependencies and run:

```sh
flutter pub get
cd ios
pod install
xcodebuild -workspace Runner.xcworkspace -scheme Runner -showdestinations
xcodebuild test -workspace Runner.xcworkspace -scheme Runner -destination 'platform=iOS Simulator,id=<available-simulator-UDID>' -only-testing:RunnerTests
```

Then test the example on physical Android and iOS devices in profile/release: cold startup, rapid forward/back scanner navigation, differently sized/off-center crops, focus and lock, pause/resume, zero widgets beyond 300 ms, app background/foreground, camera switching and rotation. Compare first visible frame separately from capture acknowledgement and record texture allocations and camera bindings. Test both Android surface backends and iOS front-camera mirroring. Automated mocks do not establish the absence of black frames on real hardware.

## Focus feedback regression repair

The first Dart overlay incorrectly held the circle visible for the entire focus lock and omitted the native lock flight. Restored the behavior from the former Android/iOS FocusView implementations: a 500 ms circle fade-in followed by 500 ms fade-out, a separate lock fade/flight (Android 300/500 ms, iOS 250/750 ms), and 200 ms unlock fade. The lock settles in the upper-left preview corner; repeated focus gestures restart the circle independently. Pausing, losing ownership or backgrounding clears feedback. Reduced motion shows the settled lock immediately.

Three rendering regression tests failed before the fix. Twelve focus tests now cover both platform timelines, repeated gestures, flight interruption, crop changes, pause/resume, backgrounding and reduced motion. `flutter test --no-pub` passes all 81 tests after this repair; `flutter analyze --no-pub` reports no issues. Navigation tests also cover ownership notifications during a different route's build; only the UI rebuild is deferred, not camera handoff. Physical-device visual verification remains outstanding.

## Readability and documentation cleanup

Preservation boundary: shared texture lifetime, per-widget configuration, focus animations, native channel contracts and cancellation/disposal ordering remain unchanged. Public and private fields, methods, constructors and lifecycle callbacks in the plugin source now have documentation describing their role, ownership or thread/queue requirements. The example's members are documented too.

- Dart compares crop fields directly instead of allocating JSON maps; the channel decodes preview replies into a typed record; models import their dependencies directly instead of the public barrel. Focus animation and capture drain code use named methods while retaining their existing sequencing.
- Removed the unused Dart `PreviewGeometry` and its three self-contained tests. Actual preview rendering tests remain. The legacy `RecognitionType` remains for compatibility with direct library imports.
- Kotlin command dispatch delegates capture startup and disposal completion to named methods. Removed unused locals, a redundant readiness check and five unreferenced native focus XML resources. Swift cleanup expands dense lifecycle/locking statements and removes the unused receiver crop argument. No new runtime abstractions or dependencies were introduced.
- Enabled `public_member_api_docs`, `directives_ordering` and `use_super_parameters`; applied four `dart fix` recommendations. The example declares the existing `flutter_lints` version so standalone analysis resolves the shared rules.

After cleanup, `flutter test --no-pub` passes 79 tests (81 minus the three tests for unused geometry, plus a regression test for unsubscribing after malformed initial metadata). Independent review caught that decoding a typed reply must release its allocated native endpoint when decoding fails; the test was red before that correction. Root and example `flutter analyze --no-pub` report no issues, and `dart fix --dry-run` reports no remaining fixes. Android unit tests pass 319 tests with no failures or skips. Android `:mlkit_scanner:lintDebug` reports no errors and four dependency-version warnings: `AndroidGradlePluginVersion` once and `GradleDependency` three times. Upgrading AGP/CameraX is outside this behavior-preserving cleanup; no warnings were suppressed.

iOS validation remains static: documentation/brace audits and comparison against the pre-cleanup executable tokens, allowing only the reviewed unused argument/import removal and modifier ordering. Xcode compilation and physical-device validation remain outstanding.

The final example `flutter build apk --debug --no-pub` also passed after the subscription cleanup correction. The APK is at `example/build/app/outputs/flutter-apk/app-debug.apk`. The reviewer confirmed the subscription finding is resolved.

## Declarative API and modal lifecycle

`BarcodeScanner` now accepts current widget state through `zoomRatio`,
`flashEnabled`, `cropRect`, `camera`, `cameraPaused`, `scanning` and `scanDelay`.
The controller remains in `lib/src`, marked internal, and is neither exported
nor delivered through a callback. Full snapshots are validated atomically;
nullable crop/camera reset defaults and unchanged rebuilds send no commands.
The example and README use state-driven configuration and per-widget retry keys.

`onError(Object)` receives initialization, validation, configuration, focus and
pause failures belonging to its widget, outside build. One shared reconciliation
failure is delivered once. A previous owner's pause failure does not become the
next owner's startup error. Initial scan results remain admissible after the real
subscription is created, even while the native start reply is pending.

The dropdown regression was reproduced before the fix: opening `PopupMenuButton`
sent `pauseCameraMethod` and `closeCapture`. The corrected widget separates route
recognition permission from camera visibility. Ordinary modals retain the lease;
a modal scanner takes ownership, then returns it on dismissal. Backgrounding
still pauses camera work, and the last actual owner may restore preview beneath
a popup after resume. Route suppression uses the last valid configuration even
when current widget arguments are invalid. Automatic route tracking follows the
nearest Navigator; outer-navigator modals require explicit `scanning` state or
presentation on the scanner's navigator, as documented in README.

Verification after these changes:

- `flutter test --no-pub`: **107 passed**, including the existing focus tests and
  new declarative API, callback, error-race, dropdown, modal handoff and lifecycle
  regression tests.
- Root `flutter analyze --no-pub` and example `dart analyze`: **no issues**.
- `dart fix --dry-run`: **nothing to fix**; formatter and `git diff --check` pass.
- Example `flutter build apk --debug --no-pub`: **passed**.
- Independent review identified the early-scan, invalid-settings/modal and
  background/popup cases; regression tests failed before their fixes and pass
  afterwards. Final review found no new functional issue in those changes.

Native sources were not changed for this API/modal update. iOS compilation and
real-device visual validation remain outstanding on this Windows environment.

## Warm manual pause

The user explicitly selected freezing preview and recognition while keeping the
camera enabled. Manual `cameraPaused` now retains the same capture lease, native
binding, texture and acknowledged controls. Flutter freezes texture rendering;
the existing native `cancelScan` command stops recognition and revokes its result
endpoint. Resume creates a fresh recognition subscription without reactivating
capture. Settings changed during pause are deferred until resume; startup or
control work already in flight may complete. Results are rejected immediately
while paused, even before the queued native cancellation completes.

Capture/release are internal controller methods. Internal channel operations are
named `activateCapture` and `stopCamera` to distinguish ownership/lifecycle from
manual pause; native wire names remain compatible. Physical release still stops
hardware on hidden routes and background apps. Background release now runs
immediately, because a paused app may never render the next frame needed for a
post-frame callback.

Regression tests first reproduced the lost ownership and unfrozen texture.
Review identified a rapid pause/resume race: resume arriving before `cancelScan`
completed was lost because the pause branch returned without checking current
intent. Its regression test failed before correction and passes afterwards.

Final verification: **115 Flutter tests passed**, root and example `dart analyze`
reported **no issues**, formatting and `git diff --check` passed, and the example
debug APK built successfully. Tests cover texture/lease reuse, no repeated zoom
or torch setup, paused startup, deferred settings, fresh scan subscriptions,
late-result rejection, handoff and physical background release. Native sources
were unchanged for this correction. Actual-device rendering latency and iOS
build validation remain unverified on this Windows environment.

## Returning to a paused scanner

Reproduced pause A -> push scanner B -> pop -> resume A. The paused checks in
both the widget and runtime prevented A from reclaiming ownership, so B's
departure sent a physical pause and closed capture. Paused visible widgets now
may take over existing selected capture demand before the outgoing owner leaves.
With no existing capture, paused widgets still wait for explicit resume; physical
background release remains unchanged. Existing capture demand can include an
already requested startup, not only a completed first frame.

The navigation regression test failed on `pauseCameraMethod` before the fix. It
now verifies return to the correct owner's settings without physical stop, frozen
preview until resume, suppression of paused results, and resume using only
`subscribeScan`/`startScan` within the returned lease. All **116 Flutter tests**
pass and root/example analyzers report no issues. Example layout shares controls
but uses an expanded preview above bottom controls on the second screen and a
240 px preview followed by controls on the first.

## Retaining each paused widget's frame

`Texture.freeze` kept a reference to the shared texture rather than independent
pixels. After scanner B refreshed that texture, returning to paused scanner A
showed B's newer image. The navigation regression now checks that A keeps the
same retained image until resume; it failed before this correction.

`FrozenPreview` copies the last painted camera layer once per pause, including
crop/rotation/fitting but excluding controls and focus feedback. It owns the
resulting `ui.Image` and disposes it on resume or removal. Pending results use
the capture future's identity and are disposed if no longer needed. No frame
bytes cross the platform channel and no generation counters were introduced.

Camera handoff awaits asynchronous rasterization before activating another
owner; rapid successive handoffs inherit this wait. Readiness is associated
with the actual painted camera layer, not a loading placeholder. A deferred
first snapshot checks current ownership so it cannot copy another scanner's
frame. A paused widget without its own frame shows the placeholder after losing
ownership. Snapshot failures use the owning scanner's existing error callback.

Final verification: **124 Flutter tests passed**, root and example analyzers
reported **no issues**, and the example debug APK built successfully. Pixel tests
cover source changes, hiding/returning, resizing, fresh snapshots after resume,
lost output and disposal. Runtime tests cover the retention barrier through
successive handoffs. Independent review found no remaining concrete issues.
External camera texture pixels on Android/iOS devices and an Xcode build remain
unverified in this Windows environment.

## Camera and recognition dependency contracts

Android `Scanner` owns the `Camera` interface, including asynchronous resource
disposal. The plugin only supplies component factories during scanner creation
and retains scanner references. It calls scanner disposal and waits for its
completion, including repeated disposal requests and replacement captures.
Lifecycle tests use a mock scanner rather than reaching into camera resources.

iOS camera output references use `CameraPreviewOutput`; the hardware coordinator
stores `BarcodeAnalyzing` and accepts an analyzer factory. `ScannerBarcode` keeps
recognition backend types out of that contract while preserving channel result
fields. A test with an injected analyzer covers reuse and subscription revocation
across ownership changes. SDK-specific helpers inside adapters remain local to
their implementations.

Camera/output references have also been removed from the iOS plugin. Preview
events flow through the owning camera and scanner; the scanner rejects stale
sources. Preview withdrawal is published after the disposal operation is
installed, so a synchronous callback cannot start replacement hardware early.

Android unit tests: **322 passed, 0 failures**. Flutter tests: **124 passed**.
The Dart channel factory was checked separately: repeated calls return the same
instance, which the runtime constructor stores without creating another channel.
The updated iOS tests and Swift compilation remain unverified because Xcode is
unavailable on this Windows machine.

## Dart and native responsibility audit

Capture arguments now contain only camera settings. The unused native
`scanEnabled` field and capture-time `scanDelay` field were removed on both
platforms. Dart alone decides when recognition starts or stops; `startScan`
supplies the current delay. While recognition is disabled, delay changes remain
in Dart. `setScanDelay` is used only to update a running scan. iOS analyzer
creation no longer applies crop settings that activation immediately reapplies.

Removing the redundant delay command exposed a reconciliation race: an update
could arrive after a comparison with no native work but before the drain future
completed. The drain now consumes requests arriving in that interval. Regression
tests cover that case, delay changes during startup, and resuming after the
example's delay popup without redundant settings commands. Startup also consumes
updates received before its ready acknowledgment. A regression varies update
timing around activation; without that fix, zoom remains at 1 instead of the
requested 2 when the update arrives two microtasks after the capture call.

Native cooldowns remain next to frame analysis to avoid unnecessary recognition
work. Native lifecycle/readiness checks, request identities, resource-release
acknowledgments, and frame-coordinate mapping protect SDK operations rather than
reimplement widget policy. `XCameraFixture` is Android test support that simulates
SDK responses; it is not an additional production controller.

Verification: **128 Flutter tests passed**, **322 Android tests passed**, root
and example Dart analyzers reported **no issues**, and the width-140 formatter
reported no changes. Updated iOS tests were reviewed statically; running them
and compiling Swift still require Xcode.

## Codebase simplification and test ownership

The native Command pattern is preserved as requested. Public widget parameters,
warm pause, retained preview frames, modal handoff, focus feedback, error routing,
and the 300 ms zero-consumer grace period remain the preservation boundaries.

The controller now accepts complete widget configurations and directly notifies
the runtime. Its three broadcast streams and imperative setting adapters have
been removed. Scan and torch callbacks belong to the owning widget and read its
latest callbacks; disposed controllers reject delivery. Camera/crop comparisons
are shared by configuration equality and capture reconciliation.

The runtime owns its idle Timer and disposal Completer directly, replacing two
wrapper classes. The cleanup barrier is published before preview withdrawal.
An additional regression registers a replacement widget synchronously from that
notification and verifies that no native resources open before disposal ends.

Android permission admission is performed once by the plugin's existing gateway.
Scanner no longer accepts a permission callback whose production implementation
always returned true. Tests for waiting, denial, and late permission callbacks
now exercise the actual plugin path rather than a second artificial gate.

Unused iOS UIImage/CGRect cropping helpers and the old native lock asset bundle
have been removed. Current recognition continues to use CameraFrameGeometry and
its existing image context; focus feedback continues to render in Flutter.

Tests are organized by the production class they exercise. Dart widget/runtime
scenarios use groups within their respective files. Android groups retain their
JUnit/Robolectric runners in the consolidated files so discovery is unchanged.
CaptureLease has its own tests. Individual native commands have individual test
files; their production implementations were not changed. The iOS texture and
geometry suites now match their actual targets, and all XCTest files have Xcode
project source entries.

Verification: **130 Flutter tests passed**, **332 Android tests passed**; root
and example analyzers reported **no issues**. Android command case counts grew
because previously aggregated assertions became separate tests. Obsolete Dart
broadcast-stream tests were replaced by tests of snapshot identity, nullable
resets, modal intent restoration, and callback suppression after disposal.
The example debug APK builds successfully. Width-140 formatting and whitespace
checks pass. Swift/XCTest execution remains unverified on Windows.

## Camera controls during manual pause

Camera controls and focus now depend on capture ownership rather than manual
pause. Recognition requires both enabled scanning and an unpaused camera.
Changes to scanning intent and delay are retained during pause and reconciled
when recognition resumes. A paused frame finishes copying before camera
controls change its source; the retained image remains unchanged until resume.

Regression tests cover controls reaching the existing capture before resume,
scanning intent changing during a pending control, stale result suppression,
frame-copy ordering with concurrent settings/resume, and retained image identity.
The controls and frame-copy tests failed before their respective fixes.

Verification: **135 Flutter tests passed**; root and example analyzers reported
**no issues**. Width-140 formatting and whitespace checks pass. Independent
static review found no remaining issues. Native code was unchanged in this fix;
physical camera behavior still requires device verification.

## Batched camera settings

One widget update now sends changed zoom, torch and crop fields in a single
`updateCameraSettings` call. Native Command implementations validate all supplied
fields before applying controls and reply after completion. Android revalidates
the capture lease between asynchronous controls. Dart acknowledges the exact
sent snapshot and coalesces later updates against it; a failed batch invalidates
that snapshot so the next update restores the complete current configuration.
Recognition commands and physical camera selection retain their lifecycle paths.

Tests cover one call per widget update, omitted fields, false torch values,
concurrent changes reverting an unfinished batch, paused-frame retention,
malformed payloads, final acknowledgment, lease revocation and partial failure.
The new one-call regression failed against the previous separate-command path.

Verification: **137 Flutter tests passed**, **339 Android tests passed** with
zero failures/errors. Root and example analyzers reported **no issues**;
width-140 formatting and whitespace checks pass. The example debug APK builds.
The new XCTest source is registered in the Xcode project; iOS compilation and
XCTest execution remain unverified on Windows. Independent static review found
no production defects; its partial-failure test recommendation was implemented.

## Argument decoding and Dart validation

Positive zoom/crop constraints belong to Dart configuration validation. Native
commands and capture payloads no longer repeat these range checks. Typed readers
still reject malformed channel values; Android `requireFiniteFloat` also rejects
overflow and nonzero values that would underflow to zero during conversion.
Native hardware limits and capture ownership checks remain native responsibilities.

Verification: **139 Flutter tests passed**, **342 Android tests passed**, including
typed conversion, malformed batch rejection, acknowledgment and capture revocation
tests. Swift tests were updated for the same decoding contract; their execution
still requires Xcode.

## Merge with remote capture lifecycle fixes

The remote `4e9f68b` changes target the removed Platform View/controller API.
The merge preserves the shared texture and declarative API, including warm
preview handoff and the existing cancellation/error ownership rules. The old
frame-cover tests and their immediate-frame binding are replaced by capture
lifecycle tests for the current protocol; shared texture visibility does not
wait for a control acknowledgment or an artificial Flutter frame barrier.

The adapted cancellation scenario exposed a lost physical-pause barrier:
releasing a capture that had not allocated its lease replaced the prior owner's
unfinished stop with an already completed close. The runtime now awaits both
lifetimes, while the original owner remains responsible for its stop error.
Both successful and failed stops reproduced the regression before the fix.

Verification after resolution: **146 Flutter tests passed**, `flutter analyze
--no-pub` reported **no issues**, and a fresh Android `testDebugUnitTest --rerun`
passed **342 tests** with zero failures, errors or skips. iOS XCTest execution
still requires macOS/Xcode; physical-device preview validation was not performed.
