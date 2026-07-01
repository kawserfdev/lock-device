import '../repositories/location_repository.dart';

/// Use case: check whether the device location (GPS) service is enabled.
///
/// Thin orchestration layer that keeps controllers free of repository
/// knowledge. Mirrors [CheckPermissionUseCase] — the pull-based half of the
/// dual-confirmation pattern for location status (the push-based half is
/// [WatchLocationStatusUseCase]).
class CheckLocationEnabledUseCase {
  /// Constructs the use case with its repository dependency.
  CheckLocationEnabledUseCase(this._repository);

  final LocationRepository _repository;

  /// Executes the use case, returning `true` when location is enabled.
  Future<bool> call() => _repository.isLocationServiceEnabled();
}
