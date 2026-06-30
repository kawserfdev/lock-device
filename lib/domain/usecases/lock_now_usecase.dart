import '../entities/app_state.dart';
import '../repositories/lock_repository.dart';

/// Use case: send the lock command to the native lock engine.
///
/// This is the enforcement entry point of the demo. It does not decide
/// *whether* to lock — that decision is driven by the static `OVERDUE` flag
/// in the presentation layer. It only executes the lock once instructed.
class LockNowUseCase {
  /// Constructs the use case with its repository dependency.
  LockNowUseCase(this._repository);

  final LockRepository _repository;

  /// Executes the use case, returning the typed lock result.
  Future<LockResult> call() => _repository.lockNow();
}
