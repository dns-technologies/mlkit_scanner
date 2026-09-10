import 'dart:async';

/// A replaceable operation; commands with the same [id] share one pending slot.
class ScannerCommand {
  /// Identifies operations that replace each other while pending, e.g. `setZoomRatio`.
  final String id;

  /// Performs the operation; the next command waits for this future to complete.
  final Future<void> Function() action;

  const ScannerCommand(this.id, this.action);
}

/// Waits for capture, then runs commands in FIFO order, retaining the latest per id.
class CommandQueue {
  CommandQueue({required this.onError});

  /// Receives command failures while the queue is open; late failures after cancellation are ignored.
  final void Function(Object error, StackTrace stack) onError;

  /// Pending commands in insertion order, with at most one entry per id.
  /// The running command is removed before its action starts.
  final _pending = <String, ScannerCommand>{};

  /// Prevents starting a second drain while this queue is processing commands.
  bool _isRunning = false;

  /// Allows pending and newly added commands to run after capture succeeds.
  bool _isCaptured = false;

  /// Permanently disables additions and error delivery after cancellation.
  bool _isClosed = false;

  /// Whether capture has completed and command execution is enabled.
  bool get isCaptured => _isCaptured;

  /// Whether this queue has been cancelled and cannot be reused.
  bool get isClosed => _isClosed;

  /// Replaces only pending work, preserving its position. Failures go to [onError].
  void add(ScannerCommand command) {
    if (_isClosed) return;
    _pending[command.id] = command;
    if (_isCaptured && !_isRunning) unawaited(_run());
  }

  /// Opens the capture barrier and waits for the accumulated work to drain.
  Future<void> captureComplete() async {
    if (_isClosed || _isCaptured) return;
    _isCaptured = true;
    await _run();
  }

  Future<void> _run() async {
    _isRunning = true;
    try {
      while (!_isClosed && _pending.isNotEmpty) {
        final command = _pending.values.first;
        _pending.remove(command.id);
        try {
          await command.action();
        } catch (error, stack) {
          if (!_isClosed) onError(error, stack);
        }
      }
    } finally {
      _isRunning = false;
    }
  }

  /// Withdraws this connection's work. Native cancellation is acknowledged separately.
  void cancel() {
    _isClosed = true;
    _isCaptured = false;
    _isRunning = false;
    _pending.clear();
  }
}
