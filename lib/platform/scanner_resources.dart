import 'dart:async';
import 'package:flutter/foundation.dart';
import 'ml_kit_channel.dart';
import 'scanner_preview.dart';

/// Owns the subscription to one native output lifetime, including its initial snapshot.
class ScannerResources {
  /// Native preview subscription transport.
  final MlKitChannel channel;

  /// Shared metadata observed by every mounted scanner preview.
  final ValueNotifier<ScannerPreviewDescription?> preview;

  /// Establishes the native subscription before a consumer opens a capture.
  late final Future<void> ready = _subscribe();

  /// Dart listener installed before requesting the initial native snapshot.
  StreamSubscription<PreviewEvent>? _events;

  /// Native endpoint handle assigned by the subscription reply.
  String? _subscriptionId;

  /// Immediately rejects events once resource cleanup begins.
  bool _closed = false;

  ScannerResources(this.channel, this.preview);

  /// Buffers early events so an older subscription reply cannot replace them.
  Future<void> _subscribe() async {
    final pending = <String, ScannerPreviewDescription?>{};
    _events = channel.previewEvents.listen((event) {
      if (_closed) return;
      if (_subscriptionId == null) {
        pending[event.subscriptionId] = event.description;
      } else if (event.subscriptionId == _subscriptionId) {
        preview.value = event.description;
      }
    });
    final reply = await channel.subscribePreview();
    final id = reply.subscriptionId;
    if (_closed) {
      await channel.unsubscribePreview(id);
      return;
    }
    _subscriptionId = id;
    preview.value = pending.containsKey(id) ? pending[id] : reply.description;
    pending.clear();
  }

  /// Revokes local delivery immediately, then removes the native endpoint.
  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    unawaited(_events?.cancel());
    final id = _subscriptionId;
    if (id != null) await channel.unsubscribePreview(id);
  }
}
