import 'dart:async';
import 'package:flutter/foundation.dart';
import 'ml_kit_channel.dart';
import 'scanner_preview.dart';

/// Owns the subscription to one native output lifetime, including its initial snapshot.
class ScannerResources {
  ScannerResources(this.channel, this.preview);

  /// Native preview subscription transport.
  final MlKitChannel channel;

  /// Publishes the latest output metadata and its withdrawal.
  final ValueNotifier<ScannerPreviewDescription?> preview;

  /// Completes after the native subscription and its initial snapshot are established.
  late final Future<void> ready = _subscribe();

  /// Dart listener installed before requesting the initial native snapshot.
  StreamSubscription<PreviewEvent>? _events;

  /// Native endpoint handle assigned by the subscription reply.
  String? _subscriptionId;

  /// Immediately rejects events once resource cleanup begins.
  bool _closed = false;

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

  /// Closes the preview subscription and prevents further event delivery.
  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    unawaited(_events?.cancel());
    final id = _subscriptionId;
    if (id != null) await channel.unsubscribePreview(id);
  }
}
