import '../repositories/kiosk_repository.dart';

/// Use case: pin the host Activity via Android Lock Task Mode so the Home and
/// Recents buttons are disabled while the Status screen is visible.
///
/// Thin orchestration layer that keeps controllers free of repository
/// knowledge. Returns `true` when the pin was started; `false` when the
/// device is not a Device Owner or the call failed.
class EnableKioskModeUseCase {
  /// Constructs the use case with its repository dependency.
  EnableKioskModeUseCase(this._repository);

  final KioskRepository _repository;

  /// Executes the use case, requesting the native kiosk pin.
  Future<bool> call() => _repository.enableKioskMode();
}
