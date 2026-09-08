import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:mlkit_scanner/platform/command_queue.dart';

void main() {
  test('commands run sequentially in insertion order', () async {
    final queue = CommandQueue();
    final firstReply = Completer<void>();
    final secondReply = Completer<void>();
    final secondStarted = Completer<void>();
    final calls = <int>[];
    final first = queue.add(() async {
      calls.add(1);
      await firstReply.future;
    });
    final second = queue.add(() async {
      calls.add(2);
      secondStarted.complete();
      await secondReply.future;
    });
    final third = queue.add(() async => calls.add(3));
    expect(calls, [1]);
    firstReply.complete();
    await secondStarted.future;
    expect(calls, [1, 2]);
    secondReply.complete();
    await Future.wait([first, second, third]);
    expect(calls, [1, 2, 3]);
  });

  test('adding inside an action does not start another worker', () async {
    final queue = CommandQueue();
    final calls = <String>[];
    late Future<void> nested;
    await queue.add(() async {
      calls.add('start');
      nested = queue.add(() async => calls.add('nested'));
      expect(calls, ['start']);
      calls.add('end');
    });
    await nested;
    expect(calls, ['start', 'end', 'nested']);
  });

  test('an idle queue starts again after being drained', () async {
    final queue = CommandQueue();
    final calls = <int>[];
    await queue.add(() async => calls.add(1));
    await queue.add(() async => calls.add(2));
    expect(calls, [1, 2]);
  });

  test('synchronous and asynchronous errors do not stop later commands',
      () async {
    final queue = CommandQueue();
    final failed = queue.add(() => throw StateError('synchronous'));
    final failedLater =
        queue.add(() async => throw ArgumentError('asynchronous'));
    final failures = Future.wait([
      expectLater(failed, throwsStateError),
      expectLater(failedLater, throwsArgumentError),
    ]);
    var completed = false;
    await queue.add(() async => completed = true);
    await failures;
    expect(completed, isTrue);
  });

  for (final fails in [false, true]) {
    test(
        'cancel settles all callers and ignores late native ${fails ? 'error' : 'success'}',
        () async {
      final queue = CommandQueue();
      final nativeReply = Completer<void>();
      final calls = <int>[];
      final running = queue.add(() async {
        calls.add(1);
        await nativeReply.future;
      });
      final pending = queue.add(() async => calls.add(2));
      queue.cancel();
      queue.cancel();
      await Future.wait([running, pending]);
      expect(queue.isClosed, isTrue);
      expect(nativeReply.isCompleted, isFalse);
      await queue.add(() async => calls.add(3));
      expect(calls, [1]);

      final replacement = CommandQueue();
      await replacement.add(() async => calls.add(4));
      if (fails) {
        nativeReply.completeError(StateError('late failure'));
      } else {
        nativeReply.complete();
      }
      await replacement.add(() async => calls.add(5));
      expect(calls, [1, 4, 5]);
    }, timeout: const Timeout(Duration(seconds: 5)));
  }

  test('an action may cancel its own queue', () async {
    final queue = CommandQueue();
    await queue.add(() async => queue.cancel());
    expect(queue.isClosed, isTrue);
    var ran = false;
    await queue.add(() async => ran = true);
    expect(ran, isFalse);
  });

  test('a capture barrier failure closes the queue and settles pending callers',
      () async {
    final queue = CommandQueue();
    final ack = Completer<void>();
    final failed = queue.add(() => ack.future, stopOnError: true);
    final failure = expectLater(failed, throwsStateError);
    var ran = false;
    final pending = queue.add(() async => ran = true);
    ack.completeError(StateError('capture failed'));
    await Future.wait([failure, pending]);
    await queue.add(() async => ran = true);
    expect(queue.isClosed, isTrue);
    expect(ran, isFalse);
  });
}
