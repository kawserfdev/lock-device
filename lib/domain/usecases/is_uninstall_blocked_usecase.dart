import '../repositories/uninstall_repository.dart';

/// Use case: query whether the uninstall-block restriction is currently active.
///
/// Thin orchestration layer that keeps controllers free of repository
/// knowledge. This is the pull-based half of the dual-path state
/// synchronisation (architecture review §16.3): the Status UI calls it on
/// startup to seed the toggle before any push event arrives.
class IsUninstallBlockedUseCase {
  /// Constructs the use case with its repository dependency.
  IsUninstallBlockedUseCase(this._repository);

  final UninstallRepository _repository;

  /// Executes the use case, returning the current blocked state.
  Future<bool> call() => _repository.isUninstallBlocked();
}
