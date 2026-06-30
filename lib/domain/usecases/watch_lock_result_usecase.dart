import '../entities/app_state.dart';
import '../repositories/lock_repository.dart';

/// Use case: observe lock-result events pushed from native.
///
/// Returns the live [Stream] of [LockResult] events emitted by the native lock
/// engine. This is the push-based half of the dual-path state synchronisation
/// (architecture review §16.3); the pull-based half is [LockNowUseCase].
///
/// The stream is essential for the background-service lock trigger: when the
/// countdown timer fires and the native lock engine locks the device, the
/// result arrives on this stream — even though no Flutter method call
/// initiated it. Without this subscription the Status UI would not learn that
/// a background lock occurred.
class WatchLockResultUseCase {
  /// Constructs the use case with its repository dependency.
  WatchLockResultUseCase(this._repository);

  final LockRepository _repository;

  /// Executes the use case, returning the lock-result stream.
  Stream<LockResult> call() => _repository.lockResults;
}
