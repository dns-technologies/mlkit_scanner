# Shared Camera Texture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Implement in this session; the reviewer agent checks the resulting changes independently. The original planned steps are retained below as design context; the execution record and validation report describe what was actually verified.

**Goal:** Replace native Platform Views with one shared camera texture, retain each controller's configuration, and dispose native resources only after Flutter observes zero widget consumers for 300 ms.

**Architecture:** Widget registrations own demand. A capture lease owns camera control, cancellation, and configuration reconciliation. Native texture outputs own frame delivery independently of widget layout. Real subscriptions identify event recipients across the platform channel; no generation counters or equivalent markers.

**Tech Stack:** Flutter/Dart, Kotlin/CameraX 1.4.2, Swift/AVFoundation, existing ML Kit integrations.

**Spec:** [Approved design with user amendments](../specs/2026-09-25-shared-camera-texture-design.md).

**Execution:** Primary agent implements; independent reviewer is already available as `/root/reviewer`. No implementation agents are needed. The user approved implementation; product changes and available automated verification are complete. See the validation record for platform limits.

## Global Constraints

- No generationId, epoch counters, or UUIDs that merely rename an attempt counter.
- Entity handles are allowed only for real registered objects that own work, subscriptions or resources and have an explicit close operation.
- Predominantly object-oriented: immutable value objects for geometry/configuration; lifetime objects for registrations, leases, subscriptions, output and disposal.
- Flutter alone owns the 300 ms timer, counted from removal of the last widget registration. Hidden and manually paused widgets still count.
- Native lifecycle enforcement remains immediate. Hardware pause differs from complete scanner disposal.
- Preserve public scanner/controller operations and per-controller retained configuration.
- Proposed version floors: Flutter >=3.29.0, Dart >=3.7.0. Keep CameraX/ML Kit dependency versions initially unchanged.
- Never transport video frames through Dart or MethodChannel.
- An unavailable Windows iOS toolchain is a verification limitation, not a passing iOS check.
- Existing `MPK-7752` working checkout contains no product edits at planning time. Preserve user files; do not create a second checkout merely for the reviewer.

## Review Focus

1. A → B → A and cancelScan → startScan must reject events already queued for a closed subscription (tasks 2, 4, 5).
2. Pending permission, first frame or camera control must not prevent replacement/closure/disposal (tasks 2, 4, 5).
3. Registration during an already-started native disposal must wait for that disposal, while registration before timer expiry cancels it (task 2).
4. Preview and recognition must agree when analysis and preview buffers have different dimensions, crop, rotation or reflection (tasks 3–5).
5. Engine detachment, surface recreation and controller disposal from inside a callback must not leak resources, resurrect callbacks or strand replies (tasks 2, 4–6).

## Object and Channel Contracts

### Flutter objects

```dart
// Widget-owned; closing removes exactly this widget from demand accounting.
abstract interface class ScannerConsumer {
  int get viewId;
  BarcodeScannerController get controller;
  Future<void> close();
}

// A concrete native ownership lease mirrored in Dart.
abstract interface class ScannerCapture {
  String get id;
  Future<void> get ready;
  void update(ScannerConfiguration desired);
  Future<void> close();
}

// Read-only preview metadata; pixel storage remains native.
enum ScannerPreviewStatus { starting, streaming, paused }

class ScannerPreviewDescription {
  final int textureId;
  final int width;
  final int height;
  final int rotationDegrees;
  final bool mirrored;
  final ScannerPreviewStatus status;

  const ScannerPreviewDescription({
    required this.textureId,
    required this.width,
    required this.height,
    required this.rotationDegrees,
    required this.mirrored,
    required this.status,
  });
}
```

Implementation names: `ScannerConsumerRegistration`, `ScannerCaptureSession`, `ScannerConfigurationReconciler`, `ScannerResultSubscription`, `ScannerResources`, `ScannerIdleDisposal`, `ScannerDisposalOperation`, `PreviewGeometry`. Do not add interfaces for classes with no substitution boundary; the above interfaces describe responsibilities, not a requirement for redundant wrappers.

The concrete `ScannerResources` owns the preview subscription, texture descriptor and close future. The concrete `ScannerCaptureSession` owns the native lease, pending activation response, latest desired configuration, acknowledged configuration and result subscription.

Implementation refinement: `ScannerRuntime` now owns the idle `Timer` and disposal
`Completer<void>` directly. The originally proposed `ScannerIdleDisposal` and
`ScannerDisposalOperation` wrappers added no independent policy. The barrier is
still published before preview withdrawal, including reentrant registration.
Widget controllers accept complete configurations and deliver results through
callbacks; no per-controller broadcast streams or imperative setting adapters
are needed. Native Command classes remain unchanged by this simplification.

An unavailable preview is represented by a null `ScannerPreviewDescription`, not a descriptor with a fictitious textureId or dimensions. Channel events carry a subscriptionId and a nullable description. A starting descriptor exists only once valid texture identity and dimensions are known.

### Native control protocol

All mutations are admitted on the native main thread. Existing platform argument validators remain in use. `viewId` identifies a registered widget; `captureId` identifies an actual native `CaptureLease`; `subscriptionId` identifies an actual registered event endpoint. Native allocates opaque UUID handles when creating those objects, never as a retry/version marker.

| Call | Input | Result and responsibility |
| --- | --- | --- |
| registerScanner | viewId | Register logical consumer; return current preview description or null; do not open camera |
| unregisterScanner | viewId | Remove consumer; close its lease only if selected; do not schedule native teardown |
| openCapture | viewId | Close previous lease's command admission, create/select new CaptureLease; return captureId promptly without awaiting permission/frames |
| resumeCameraMethod | captureId, configuration, geometry | Activate selected lease; await readiness/settings; never enable analysis here; closed leases finish cancellation without affecting replacement |
| closeCapture | captureId | Cancel this lease and its subscriptions/replies; keep shared output warm; idempotent |
| pauseCameraMethod | captureId | Stop physical capture for this selected lease; keep configuration/resources reusable |
| disposeScanner | no arguments | Cancel every lease, stop/release camera/analyzer/output and complete only after resources are safe to replace |
| subscribeScan | captureId | Create a disabled concrete native result endpoint; return subscriptionId |
| startScan | captureId, subscriptionId, delay | Enable recognition for the prepared endpoint |
| cancelScan | captureId | Close the current result endpoint and disable recognition |
| subscribePreview | no arguments | Return a concrete preview subscription handle plus current preview description, without opening camera |
| unsubscribePreview | subscriptionId | Stop publishing to this endpoint; close idempotently |
| updatePreviewGeometry | captureId, width, height | Update active visible geometry, not surface resolution |
| focus | captureId, locked | One-shot command at crop center, admitted under active lease |

Existing `setZoomRatio`, `toggleFlash`, `setCropAreaMethod`, `setScanDelay` additionally carry captureId. `getIosAvailableCameras` remains unscoped. Scan events carry subscriptionId and viewId; preview events carry their preview subscriptionId and description; torch events carry captureId and viewId. These objects own event delivery and are closed explicitly.

On camera change using the same texture, native closes the old binding's publication eligibility before selecting a new binding. Events are published on main in binding order. No old frame callback may publish metadata after replacement. Flutter preview subscriptions are replaced when ScannerResources is replaced, including disposal/recreation of a possibly reused textureId.

Initial activation sequence is openCapture → resumeCameraMethod → subscribeScan → install Dart result subscription → startScan, with the last three steps only when the latest desired scanEnabled is true. The snapshot passed to resume retains desired configuration, but scanEnabled is not an instruction to begin frame analysis there. A stale subscribeScan response is immediately closed; it never enables recognition. cancelScan synchronously revokes the Dart result endpoint before the reconciler waits for any other native command.

Runtime releases with a concrete reason. Hidden/manual-pause releases request hardware pause while the lease is still selected, then close it after acknowledgement. Widget removal closes the lease and lets the zero-widget timer own full disposal; when other registered widgets remain but none is visible, hardware is paused before closing. A direct A→B selection bypasses the pause path entirely. Replacement may close a lease while its pause is pending: native checks that lease at the point of side effects, and serializes session stop/start operations so an old pause cannot stop the new owner's camera.

## Task 1: Value objects and transport contracts

**Files:**

- Create `lib/platform/scanner_preview.dart`, `lib/platform/scanner_capture.dart`, `lib/platform/scanner_result_subscription.dart`.
- Modify `lib/platform/ml_kit_channel.dart`, `lib/platform/scanner_configuration.dart`.
- Modify `test/platform/ml_kit_channel_test.dart`, `test/platform/scanner_configuration_test.dart`, `test/support/runtime_harness.dart`.
- Create `test/platform/scanner_preview_test.dart` only if not already present; otherwise extend the existing file.

**Interfaces:** `MlKitChannel` exposes the protocol above as typed methods; session code receives lease/subscription handles and preview value objects, not arbitrary maps. Configuration values compare by content, including camera and crop.

- Write channel tests for validated descriptor decoding, entity handles, malformed events and typed camera-control errors. Add value equality coverage before implementing it:

```dart
test('independently constructed equivalent configurations compare equal', () {
  const first = ScannerConfiguration(zoomRatio: 2);
  final second = first.copyWith();
  expect(first, second);
  expect(first.hashCode, second.hashCode);
});
```

- Run `flutter test test/platform/ml_kit_channel_test.dart test/platform/scanner_configuration_test.dart`; verify the new contract/equality tests fail for missing behavior.
- Implement explicit decode methods and immutable values. Unknown subscription handles are ignored, malformed numeric geometry is rejected, and missing required entity handles never default to the selected owner.
- Extend the test harness with native fixture objects for Consumer, CaptureLease and Subscription. Each stores pending completions and closes them explicitly. Mock only the channel boundary; keep runtime and controller real.
- Re-run the task tests. Commit only this task's reviewed files when implementation is being committed; never stage unrelated user changes.

## Task 2: Widget demand, capture objects and configuration reconciliation

**Files:**

- Modify `lib/platform/scanner_runtime.dart`, `lib/platform/scanner_controller.dart`.
- Create `lib/platform/scanner_consumer.dart`, `lib/platform/scanner_resources.dart`, `lib/platform/scanner_configuration_reconciler.dart`.
- Replace and then remove `lib/platform/command_queue.dart` and its queue-specific tests after the new behavioral tests pass.
- Update `test/platform/scanner_runtime_test.dart`, `test/platform/scanner_runtime_race_test.dart`, `test/platform/scanner_pause_test.dart`, `test/platform/scanner_controller_test.dart`.

**Interfaces:** Runtime registers a widget explicitly and returns `ScannerConsumerRegistration`. Controller construction alone must not count as a widget or keep a camera alive. Runtime publishes current shared preview and selected session state; widget lifecycle invokes registration/close and visible capture selection.

- Add lifecycle tests using WidgetTester fake time. The fixture exposes `mount(viewId)` (controller plus widget registration), `unmount(viewId)`, `disposedScannerCount`, `blockDisposal()` (a Completer), and `startedCaptures` (concrete capture fixture objects). Define these fixture operations in `runtime_harness.dart`; they must run the real registration/runtime methods.

```dart
testWidgets('zero consumers for 300 ms disposes scanner once', (tester) async {
  final h = RuntimeHarness();
  await h.mount(1);
  await h.unmount(1);
  await tester.pump(const Duration(milliseconds: 299));
  expect(h.disposedScannerCount, 0);
  await tester.pump(const Duration(milliseconds: 1));
  expect(h.disposedScannerCount, 1);
  await h.dispose();
});

testWidgets('a new consumer cancels idle disposal before expiry', (tester) async {
  final h = RuntimeHarness();
  await h.mount(1);
  await h.unmount(1);
  await tester.pump(const Duration(milliseconds: 299));
  await h.mount(2);
  await tester.pump(const Duration(seconds: 1));
  expect(h.disposedScannerCount, 0);
  await h.dispose();
});
```

- Also write tests for hidden/paused registered consumers, controller-only construction, registration during disposal, duplicate close, A→B→A stale event rejection, pending permission/start cancellation, and disposal inside a result callback. Record the expected sequence: old capture closes, its pending response completes, new capture configures; no hardware pause on a direct handoff.
- Add explicit tests that hidden registered widgets pause hardware without starting disposal; a replacement during a pending pause cannot stop the new capture; initial scanEnabled does not enable analysis before its endpoint is installed; cancelScan revokes Dart delivery while a zoom command is blocked.
- Run `flutter test test/platform/scanner_runtime_test.dart test/platform/scanner_runtime_race_test.dart`; inspect RED results before runtime edits.
- Replace controller stream subscriptions as demand counters with widget-owned registration objects. Mark a controller disposed synchronously; defer only stream/notifier closure when needed for reentrancy.
- Implement the timer transaction:

```text
last widget close:
  detach current consumer immediately
  publish ScannerIdleDisposal object with 300 ms Timer
timer callback:
  if this object is no longer pending or consumers are present: return
  publish ScannerDisposalOperation before calling native
  invalidate this ScannerResources preview subscription
  await disposeScanner
new widget:
  cancel a still-pending ScannerIdleDisposal synchronously
  await any already-published ScannerDisposalOperation before opening resources
```

- Implement a reconciler inside each capture. Publish the session before the first await; every awaited operation is followed by an identity check. Recompute the next differing field from latest desired vs acknowledged state. Apply camera selection before dependent controls, then geometry/crop, zoom, torch, scan delay and scan enablement. One-shot focus requests use the same admission boundary and cannot cross a closed lease.
- Test zoom coalescing with the first setter blocked: request 2, then 3, then 4; complete 2; next applied value must be 4. Test a field reverted to its acknowledged value while another command is running; do not send a redundant setter. Test failure followed by an explicit new state update: invalidate acknowledged state, recover from a complete snapshot once, do not spin on the failed snapshot.
- Keep capture replacement, native cancellation and disposal out of the per-capture reconciliation loop. If a native lease allocation reply arrives after its Dart session closed, close the returned real lease instead of applying its configuration.
- Replace queue-shaped expectations in existing tests with effects on camera state and ownership. Run all `test/platform` tests and remove the obsolete queue implementation only after its behavior is covered.

## Task 3: Flutter texture, geometry and overlays

**Files:**

- Modify `lib/widgets/camera_preview.dart`, `lib/widgets/barcode_scanner.dart`.
- Create `lib/widgets/scanner_overlay.dart`, `lib/models/preview_geometry.dart`.
- Update `test/widgets/camera_preview_test.dart`, scanner widget tests under `test/widgets`, and `test/models/preview_geometry_test.dart`.

**Interfaces:** `PreviewGeometry` takes source dimensions, unapplied quarter-turn rotation, mirroring and widget size. It computes visible buffer bounds and maps normalized widget points/rectangles into oriented source coordinates. `CameraPreview` consumes the shared descriptor and displays Texture; it does not register a Platform View or start hardware.

- Write tests expecting a Texture with the descriptor's textureId and no PlatformViewLink/UiKitView. Two mounted widgets must display the same id while retaining different consumer ids and configurations.
- Write geometry tests from explicit reference cases before implementing transforms:

```text
Source 1280×720, rotation 0, widget 400×400:
  visible source = (280, 0, 720, 720)
  widget center → source (640, 360)
  normalized widget point (0.25, 0.5) → source (460, 360)
  mirrored version of that point → source (820, 360)
Source 1280×720, rotation 90, widget 400×400:
  oriented source = 720×1280
  visible oriented source = (0, 280, 720, 720)
```

- Run widget/geometry tests to observe RED. Implement transforms without inferring source aspect from the device screen; validate finite dimensions and clip intersections. Add all rotations, noncentral crop, offscreen crop and unequal preview/analysis dimensions to the shared geometry fixture cases.
- Render `Texture` inside the transform/cover/clip layout. Fill the widget with solid black only when this resource has no displayable image; do not gate warm preview on completion of configuration reconciliation.
- Move visor/focus visuals into a CustomPainter and GestureDetector. Preserve center-of-crop focus and long-press locking. Submit gestures only for the current capture; obsolete widgets must never focus the new owner.
- Connect explicit registration to widget init/dispose; notify viewport changes after layout only when changed. Keep controller callback timing and error delivery coherent on registration failure.
- Verify warm navigation, manual pause, cold placeholder, error callback, overlays, disposing inside onScan, and resize without surface allocation with `flutter test test/widgets test/models/preview_geometry_test.dart`.

## Task 4: Android output and native capture leases

**Files:**

- Modify `android/src/main/kotlin/com/dns_technologies/mlkit_scanner/MlkitScannerPlugin.kt` and `PluginConstants.kt`.
- Modify `scanner/Scanner.kt`, `scanner/components/camera/Camera.kt`, `scanner/components/camera/CameraConnection.kt`, `scanner/components/camera/x/XCamera.kt`, `scanner/components/camera/x/XCameraFrame.kt` under that package.
- Create `scanner/ScannerConsumer.kt`, `scanner/CaptureLease.kt`, `scanner/components/camera/x/CameraTextureOutput.kt` and pure preview geometry support.
- Update command argument parsing to require captureId for scoped controls.
- Retire ScannerView/ScannerViewFactory and native UI overlay classes/tests after migration.
- Update corresponding tests under `android/src/test/kotlin/com/dns_technologies/mlkit_scanner`; add `CameraTextureOutputTest.kt` and `CaptureLeaseTest.kt`.

**Interfaces:** The plugin retains TextureRegistry and Application context. CaptureLease owns pending operation completion and subscriptions, while Scanner owns the shared Camera/analyzer. CameraTextureOutput owns SurfaceProducer and each actual SurfaceRequest; XCamera consumes this output and logical viewport geometry instead of an Android View.

- Write tests verifying one producer and one camera binding for two consumer captures; manually invoke a closed SurfaceRequest's completion and ensure it cannot tear down the current request. Verify invalid lease commands fail before camera methods run.
- Write tests for pending permission/start/control cancellation, delayed result after A→B→A, unregister of inactive consumer, no native scheduled expiry, and repeated dispose completing replies once.
- Run the Android unit-test task from the example Gradle root and inspect the expected missing-behavior failures. Use the installed JBR for JAVA_HOME when necessary.
- Implement SurfaceProducer lifecycle: set size from SurfaceRequest before getSurface; provide surface once; close/release each request according to its result callback. Surface cleanup invalidates the matching request object and prevents writes before recovery. A view resize updates crop/focus geometry, not producer size.
- Use CameraX transform metadata plus producer.handlesCropAndRotation to calculate only unapplied Flutter transforms. Preserve ImageAnalysis close discipline and geometry agreement. Do not claim the first analysis frame means Flutter displayed preview.
- Implement native CaptureLease registry/selection and result endpoints. Replacement closes the old lease's admission and pending replies on main; a pending SDK future is not proof that hardware side effects were undone. Apply the new lease's requested state after prior work has been cancelled/settled according to CameraX control semantics.
- Remove native 300 ms handler logic. Expose explicit pause, closeCapture and disposeScanner semantics. Engine/Activity detach closes native work immediately; no Flutter timer is needed for native lifecycle safety.
- Migrate focus to the canonical coordinate transform and CameraX metering APIs without PreviewView. Keep target rotation updates and lifecycle binding recovery supported.
- Run Android unit tests and debug APK build. Check no production references to PreviewView, PlatformView factory or idle-disposal timer remain.

## Task 5: iOS texture and native capture leases

**Files:**

- Modify `ios/Classes/SwiftMlkitScannerPlugin.swift`, `PluginConstants.swift`, `Models/ScannerHardware.swift`, `Models/ScannerDevice.swift`, `Models/CameraPreviewing.swift`, `CameraPreview.swift`.
- Create `ios/Classes/Models/ScannerConsumer.swift`, `Models/CaptureLease.swift`, `CameraTextureOutput.swift`.
- Modify `PreviewGeometry.swift`, `MlKit/RecognitionHandler.swift`, `MlKit/MlkitBarcodeScanner.swift`, `Extensions/UIImageExtension.swift` as needed to use actual viewport geometry.
- Update scoped command parsing and scanner commands.
- Retire native ScannerView, ScannerOverlay and FocusView along with corresponding obsolete UI tests/references.
- Update `example/ios/RunnerTests` and add texture/lease tests in its existing groups.

**Interfaces:** CameraTextureOutput implements FlutterTexture and owns a retained pixel buffer plus registry registration. ScannerHardware owns camera resources and selected CaptureLease. Analysis subscriptions are independent of preview delivery and retain their actual endpoint identity.

- Write XCTest cases for pixel buffer replacement/retention, callback after dispose, close during first-frame wait, A→B→A late scan delivery and consumer registration not allocating camera.
- Add geometry reference cases from task 3, including iOS front-camera mirroring, cropped widget sizes and aspect ratios independent of UIScreen bounds.
- On macOS run RunnerTests first to observe RED; on Windows record that tests are written but cannot be executed and preserve the exact macOS verification command in the validation document.
- Replace AVCaptureVideoPreviewLayer/UIKit container with FlutterTexture publication. Protect latest-buffer access with a dedicated lock; copyPixelBuffer returns a retained buffer. Dispose prevents future publication and unregisters only after the object stops accepting frames.
- Configure and mutate AVCaptureSession on its serial session queue. Marshal result publication to main; callbacks check their actual camera binding/capture/subscription object before delivery. Layout is no longer a camera-start condition.
- Remove Timer-based native disposal. Add explicit hardware pause and full resource disposal acknowledgements. A direct capture handoff retains session/output; a different physical camera reconfigures input and invalidates the old binding's publication eligibility.
- Update recognition to consume the canonical visible crop instead of screen-based scale factors. Preview publication remains available when recognition is disabled, slow or failing.
- Build/test on macOS with Xcode when available; otherwise perform source/contract review and report iOS build/runtime validation as outstanding, never passed.

## Task 6: Integration, documentation and independent review

**Files:** `pubspec.yaml`, `README.md`, `CHANGELOG.md`, `example/lib/main.dart`, and `docs/superpowers/validation/2026-09-25-shared-camera-texture.md`.

- Raise Flutter/Dart constraints and align declared platform floors with that SDK. Preserve dependency versions unless a verified incompatibility requires a documented change.
- Extend the example with A/B scanner routes using different crop/zoom configurations, cancellation/resume and navigation back; leave ordinary app use straightforward.
- Document shared-stream behavior, widget-count-based lifetime, manual pause, the solid black loading state, logical viewId, entity-scoped control and limitations of independent frozen previews.
- Run `flutter test`, `flutter analyze --no-fatal-infos`, Android unit tests and `flutter build apk --debug` from the example. Record exact commands, counts and failures. Run formatter and `git diff --check`.
- Search product changes for `generationId`, epoch/attempt counters, native grace timers, Platform View registration, and stale queue imports. Investigate each hit; do not add renamed epoch identifiers to make the search pass.
- Ask `/root/reviewer` to review the complete product/test diff against the spec and latest user constraints. Include validation results and unresolved platform limits. Reviewer edits no product files; implementer fixes supported findings with regression tests.
- On real Android/iOS devices compare baseline vs new preview in profile/release for cold start, warm A→B→A, >300 ms with zero widgets, pause/resume, rotation and background recovery. Record texture allocation/binding counts and first visible frame separately from capture completion; mock readiness is not visual evidence.
- Deliver the changed files, passed checks and explicit iOS/device verification limits. No claim of eliminated black frames until device evidence supports it.

## Decisions from Independent Architecture Review

- Use native lease and subscription handles for real objects: object identity alone inside one language cannot identify an event already queued across a channel.
- Do not use a single global awaited command queue. Cancellation and ownership changes bypass per-lease state reconciliation.
- Count widget registration objects, not controller construction or current camera ownership.
- Clear ownership synchronously on disposal; delay only event-stream cleanup needed for callback reentrancy.
- Fence disposal with its actual operation future. A cancelled Timer cannot undo a native dispose already in progress.

## Self-review

The plan covers all approved areas: output lifetime, native cleanup removal, widget demand, command admission, shared preview, geometry, focus, scan result ownership, failure/cancellation, docs and validation. User amendments take precedence over the original generation and native timer paragraphs. Independent review corrections incorporated: unavailable uses a null descriptor; channel contracts are entity-scoped; hidden-owner pause happens before closure; initial analysis starts after endpoint registration and cancellation revokes delivery immediately. Implementation and independent review are complete; device measurements and Xcode validation remain outstanding.

## Execution record

See [validation results and platform limits](../validation/2026-09-25-shared-camera-texture.md). Related lifetime objects are co-located with their owners where a separate file would not improve clarity. Native UI implementations and the obsolete Dart command queue were removed. The example includes a second scanner route. iOS tests were migrated but are not marked as executed; real-device timing is also outstanding.
