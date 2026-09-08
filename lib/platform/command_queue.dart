import 'dart:async';
import 'dart:collection';

/// Runs commands in FIFO order and settles discarded calls immediately on cancellation.
class CommandQueue {
  final _pending = Queue<_QueuedCommand>();
  bool _isRun = false;
  bool _isClosed = false;

  bool get isClosed => _isClosed;

  Future<void> add(Future<void> Function() action, {bool stopOnError = false}) async {
    if (_isClosed) return;
    final command = _QueuedCommand(action, stopOnError);
    _pending.addLast(command);
    unawaited(_run());
    return command.reply.future;
  }

  Future<void> _run() async {
    if (_isRun) return;

    _isRun = true;
    try {
      while (_pending.isNotEmpty) {
        final command = _pending.first;
        try {
          await command.action();
          if (!command.reply.isCompleted) command.reply.complete();
        } catch (error, stack) {
          if (!command.reply.isCompleted) {
            command.reply.completeError(error, stack);
          }
          if (command.stopOnError) cancel();
        } finally {
          if (_pending.isNotEmpty) _pending.removeFirst();
        }
      }
    } finally {
      _isRun = false;
    }
  }

  /// Withdraws this connection's work. Native cancellation is acknowledged separately.
  void cancel() {
    _isClosed = true;
    while (_pending.isNotEmpty) {
      final command = _pending.removeFirst();
      if (!command.reply.isCompleted) command.reply.complete();
    }
  }
}

class _QueuedCommand {
  final Future<void> Function() action;
  final reply = Completer<void>();

  final bool stopOnError;

  _QueuedCommand(this.action, this.stopOnError);
}
