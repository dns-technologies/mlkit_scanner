# Declarative BarcodeScanner API

User request authorizes the API change and implementation. Preserve shared texture reuse, real lease/subscription identity, Flutter's 300 ms widget-demand grace period, focus animations and documentation of every member.

## Contract

- Remove `onScannerInitialized`, `onCameraInitializeError` and controller exports.
- Replace initial-only settings with `zoomRatio = 1`, `flashEnabled = false`, nullable `cropRect` and nullable iOS `camera`.
- Add `cameraPaused = false`, `scanning = false`, `scanDelay = 0` (milliseconds).
- Keep `onScan` and `onChangeFlashState`; show solid black before preview output is available.
- Keep the controller as an internal `lib/src` implementation, owned privately by widget state. No controller getters or controller callbacks on the public widget.
- Apply complete validated snapshots in initState/didUpdateWidget; null crop/camera resets the platform defaults. Equivalent settings do not send redundant commands. Callbacks may change without recreating capture.
- Optional `onError(Object)` receives initialization, validation, configuration, focus and pause failures belonging to this mounted widget. Preserve typed CameraControlException instances and stack traces for fallback FlutterError reporting. Do not route an old owner's errors to a new widget.
- Error delivery during build is deferred so parent state can replace the failed widget safely. No callbacks after disposal. Shared cleanup without remaining widgets uses FlutterError.
- User correction: non-opaque modal routes without scanners suspend recognition only, retaining live preview and capture. A scanner inside a modal takes ownership and returns it on dismissal. Opaque pages and background apps still stop camera work. Route suppression must remain independent of invalid property updates.
- User selected warm manual pause: `cameraPaused` freezes Flutter Texture rendering and stops native recognition while retaining the live capture, camera binding and controls. Resume creates a fresh recognition endpoint, without capture reactivation. Camera settings and focus apply during pause without replacing the retained image. Recognition requires both an unpaused camera and enabled scanning; delay changes apply when recognition resumes. Hidden/background release still physically stops hardware. Capture/release remain internal controller operations, never widget parameters.
- Paused-frame correction: retain an independent image per paused widget, not only `Texture.freeze`. Await pending rasterization before camera handoff, exclude placeholders from snapshots, and discard late snapshots after resume/disposal. Navigation must preserve the original paused image even while another widget changes the shared texture.

## Implementation sequence

1. Add widget tests for declarative initial/update settings, pause/scanning distinctions, null reset, pending-start coalescing, error ownership, safe parent rebuild and disposal.
2. Move the controller into internal source, add atomic snapshot validation/application and owner-specific error reporting. Keep existing imperative methods internal for runtime tests and implementation compatibility.
3. Update widget lifecycle and runtime/capture error paths without moving native contracts or weakening cancellation barriers.
4. Migrate all widget tests, example and README to state-driven controls. Public package exports must contain no controller.
5. Run formatter, Dart fixes/analyzer, Flutter tests and example APK build. Ask the existing reviewer to check updates and error routing.

## Validation focus

Fast A/B handoff; start/cancel during pending controls; pause during pending startup; distinct widgets and callback replacement; bad settings leave no partial snapshot; typed control errors delivered once; current callbacks invoked outside build; removed widgets ignore late errors. Recreating a failed scanner uses a new widget key and its ordinary widget lifecycle.
