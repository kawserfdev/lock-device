import '../repositories/location_repository.dart';

/// Use case: open the system location settings screen.
///
/// Used by the Welcome screen to direct the user to the OS location toggle
/// when GPS is off and they tap Continue. Mirrors the thin use-case style of
/// the rest of the domain layer.
class OpenLocationSettingsUseCase {
  /// Constructs the use case with its repository dependency.
  OpenLocationSettingsUseCase(this._repository);

  final LocationRepository _repository;

  /// Executes the use case, returning `true` when settings was launched.
  Future<bool> call() => _repository.openLocationSettings();
}
