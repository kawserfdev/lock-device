import '../entities/app_state.dart';
import '../repositories/lock_repository.dart';

/// Use case: lock the device for a user-specified duration.
///
/// Unlike [LockNowUseCase] which opens the default lockout window, this use
/// case passes a custom [durationMs] so the device cannot be unlocked (any
/// unlock attempt is immediately re-locked by the native LockoutManager) until
/// the duration elapses. This is the enforcement entry point for the
/// user-driven "timed forced-lock" feature.
class LockForDurationUseCase {
  /// Constructs the use case with its repository dependency.
  LockForDurationUseCase(this._repository);

  final LockRepository _repository;

  /// Executes the use case, returning the typed lock result.
  ///
  /// [durationMs] is the lockout window length in milliseconds.
  Future<LockResult> call(int durationMs) =>
      _repository.lockForDuration(durationMs);
}
