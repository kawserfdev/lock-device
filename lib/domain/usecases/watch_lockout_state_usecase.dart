import '../repositories/lock_repository.dart';

/// Use case: observe lockout-state events pushed from native.
///
/// Returns the live [Stream] of `bool` events emitted by the native
/// [LockoutManager]. Emits `true` when the post-lock lockout window opens
/// (unlock attempts will be re-locked) and `false` when it elapses.
///
/// This is the push-based half of the dual-path state synchronisation
/// (architecture review §16.3). The Status UI subscribes so it can show the
/// lockout countdown the moment a successful lock occurs — whether triggered
/// manually via [LockNowUseCase] or automatically by the background countdown
/// timer.
class WatchLockoutStateUseCase {
  /// Constructs the use case with its repository dependency.
  WatchLockoutStateUseCase(this._repository);

  final LockRepository _repository;

  /// Executes the use case, returning the lockout-state stream.
  Stream<bool> call() => _repository.lockoutStateChanges;
}
