import 'dart:math';

/// Ownership of an addressable endpoint until it is permanently closed.
class ScannerLease {
  static final _random = Random.secure();

  /// Opaque endpoint address used for native transport.
  final String id = List.generate(4, (_) => _random.nextInt(0x100000000).toRadixString(16).padLeft(8, '0')).join();

  bool _closed = false;

  /// Whether this lease has permanently stopped admitting events.
  bool get isClosed => _closed;

  /// Accepts events only from this lease while it remains open.
  bool accepts(String? endpointId) => !_closed && endpointId == id;

  /// Permanently revokes event admission.
  void close() => _closed = true;
}
