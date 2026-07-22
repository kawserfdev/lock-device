import '../repositories/kiosk_repository.dart';

/// Use case: release the Lock Task Mode pin applied by
/// [EnableKioskModeUseCase] and restore normal navigation.
///
/// Thin orchestration layer that keeps controllers free of repository
/// knowledge. Returns `true` when the pin was stopped (or there was nothing
/// to stop); `false` only when an unexpected error occurred.
class DisableKioskModeUseCase {
  /// Constructs the use case with its repository dependency.
  DisableKioskModeUseCase(this._repository);

  final KioskRepository _repository;

  /// Executes the use case, requesting the native kiosk release.
  Future<bool> call() => _repository.disableKioskMode();
}
