import '../repositories/uninstall_repository.dart';

/// Use case: apply or remove the uninstall-block restriction.
///
/// Thin orchestration layer that keeps controllers free of repository
/// knowledge. Returns `true` when the restriction was actually applied/removed;
/// `false` when the app is not a Device/Profile Owner or the call failed.
class SetUninstallBlockedUseCase {
  /// Constructs the use case with its repository dependency.
  SetUninstallBlockedUseCase(this._repository);

  final UninstallRepository _repository;

  /// Executes the use case, applying (`true`) or removing (`false`) the
  /// restriction.
  Future<bool> call(bool blocked) => _repository.setUninstallBlocked(blocked);
}
