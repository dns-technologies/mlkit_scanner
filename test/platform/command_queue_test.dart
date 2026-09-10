import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/platform/command_queue.dart';

void main() {
  late CommandQueue queue;
  setUp(() => queue = CommandQueue(onError: Error.throwWithStackTrace));
  tearDown(() => queue.cancel());

  test('commands wait for capture and then run sequentially', () async {
    final firstReply = Completer<void>();
    final secondReply = Completer<void>();
    final secondStarted = Completer<void>();
    final calls = <int>[];
    queue.add(ScannerCommand('first', () async {
      calls.add(1);
      await firstReply.future;
    }));
    queue.add(ScannerCommand('second', () async {
      calls.add(2);
      secondStarted.complete();
      await secondReply.future;
    }));
    queue.add(ScannerCommand('third', () async => calls.add(3)));
    expect(queue.isCaptured, isFalse);
    expect(calls, isEmpty);
    final capture = queue.captureComplete();
    expect(queue.isCaptured, isTrue);
    expect(calls, [1]);
    firstReply.complete();
    await secondStarted.future;
    expect(calls, [1, 2]);
    secondReply.complete();
    await capture;
    expect(calls, [1, 2, 3]);
  });

  test('replacement retains its position and runs only the latest action', () async {
    final ack = Completer<void>();
    final calls = <String>[];
    queue.add(ScannerCommand('zoom', () async => calls.add('old zoom')));
    queue.add(ScannerCommand('torch', () async => calls.add('torch')));
    queue.add(ScannerCommand('zoom', () async {
      calls.add('latest zoom');
      await ack.future;
    }));
    final capture = queue.captureComplete();
    expect(calls, ['latest zoom']);
    ack.complete();
    await capture;
    expect(calls, ['latest zoom', 'torch']);
  });

  test('a running command is not replaced and only the latest pending value follows it', () async {
    await queue.captureComplete();
    final ack = Completer<void>();
    final completed = Completer<void>();
    final calls = <int>[];
    queue.add(ScannerCommand('zoom', () async {
      calls.add(1);
      await ack.future;
    }));
    queue.add(ScannerCommand('zoom', () async => calls.add(2)));
    queue.add(ScannerCommand('zoom', () async {
      calls.add(3);
      completed.complete();
    }));
    await queue.captureComplete();
    expect(calls, [1]);
    ack.complete();
    await completed.future;
    expect(calls, [1, 3]);
  });

  test('captureComplete drains commands arriving during configuration', () async {
    final ack = Completer<void>();
    final calls = <int>[];
    queue.add(ScannerCommand('zoom', () async {
      calls.add(1);
      await ack.future;
    }));
    var drained = false;
    final capture = queue.captureComplete().then((_) => drained = true);
    queue.add(ScannerCommand('crop', () async => calls.add(2)));
    expect(drained, isFalse);
    ack.complete();
    await capture;
    expect(calls, [1, 2]);
  });

  test('adding inside an action does not start another worker', () async {
    final calls = <String>[];
    queue.add(ScannerCommand('zoom', () async {
      calls.add('start');
      queue.add(ScannerCommand('zoom', () async => calls.add('nested')));
      expect(calls, ['start']);
      calls.add('end');
    }));
    await queue.captureComplete();
    expect(calls, ['start', 'end', 'nested']);
  });

  test('an idle captured queue runs new commands immediately', () async {
    final calls = <int>[];
    queue.add(ScannerCommand('zoom', () async => calls.add(1)));
    await queue.captureComplete();
    queue.add(ScannerCommand('zoom', () async => calls.add(2)));
    expect(calls, [1, 2]);
  });

  for (final captured in [false, true]) {
    test('errors are reported and later commands run with captured=$captured', () async {
      final errors = <Object>[];
      final stacks = <StackTrace>[];
      queue = CommandQueue(onError: (error, stack) {
        errors.add(error);
        stacks.add(stack);
      });
      if (captured) await queue.captureComplete();
      final completed = Completer<void>();
      queue.add(ScannerCommand('first', () => throw StateError('synchronous')));
      queue.add(ScannerCommand('second', () async => throw ArgumentError('asynchronous')));
      queue.add(ScannerCommand('last', () async => completed.complete()));
      if (captured) {
        await completed.future;
      } else {
        await queue.captureComplete();
      }
      expect(completed.isCompleted, isTrue);
      expect(errors, [isStateError, isArgumentError]);
      expect(stacks, everyElement(isA<StackTrace>()));
    });
  }

  test('a replaced command reports only the latest action failure', () async {
    final errors = <Object>[];
    queue = CommandQueue(onError: (error, stack) => errors.add(error));
    queue.add(ScannerCommand('zoom', () async => throw ArgumentError('replaced')));
    queue.add(ScannerCommand('zoom', () async => throw StateError('latest')));
    await queue.captureComplete();
    expect(errors, [isStateError]);
  });

  for (final fails in [false, true]) {
    test('cancel discards pending work and ignores late native ${fails ? 'error' : 'success'}', () async {
      final nativeReply = Completer<void>();
      final calls = <int>[];
      queue.add(ScannerCommand('zoom', () async {
        calls.add(1);
        await nativeReply.future;
      }));
      final capture = queue.captureComplete();
      queue.add(ScannerCommand('zoom', () async => calls.add(2)));
      queue.cancel();
      queue.cancel();
      expect(queue.isClosed, isTrue);
      expect(queue.isCaptured, isFalse);
      expect(nativeReply.isCompleted, isFalse);
      await queue.captureComplete();
      queue.add(ScannerCommand('zoom', () async => calls.add(3)));
      expect(calls, [1]);

      final replacement = CommandQueue(onError: Error.throwWithStackTrace);
      replacement.add(ScannerCommand('zoom', () async => calls.add(4)));
      await replacement.captureComplete();
      if (fails) {
        nativeReply.completeError(StateError('late failure'));
      } else {
        nativeReply.complete();
      }
      await capture;
      replacement.add(ScannerCommand('zoom', () async => calls.add(5)));
      expect(calls, [1, 4, 5]);
    }, timeout: const Timeout(Duration(seconds: 5)));
  }

  test('cancellation before capture clears pending work permanently', () async {
    var ran = false;
    queue.add(ScannerCommand('zoom', () async => ran = true));
    queue.cancel();
    await queue.captureComplete();
    expect(ran, isFalse);
    expect(queue.isCaptured, isFalse);
  });

  test('an action may cancel its own queue', () async {
    queue.add(ScannerCommand('cancel', () async => queue.cancel()));
    var ran = false;
    queue.add(ScannerCommand('zoom', () async => ran = true));
    await queue.captureComplete();
    expect(queue.isClosed, isTrue);
    queue.add(ScannerCommand('zoom', () async => ran = true));
    expect(ran, isFalse);
  });

  test('the error handler may cancel the queue before the next command runs', () async {
    queue = CommandQueue(onError: (error, stack) => queue.cancel());
    queue.add(ScannerCommand('first', () async => throw StateError('failed')));
    var ran = false;
    queue.add(ScannerCommand('second', () async => ran = true));
    await queue.captureComplete();
    expect(queue.isClosed, isTrue);
    expect(ran, isFalse);
  });
}
