import '../repositories/location_repository.dart';

/// Use case: observe device location (GPS) service on/off changes.
///
/// Returns the live [Stream] of `bool` events emitted by the `geolocator`
/// plugin's `ServiceStatus` stream. This is the push-based half of the
/// dual-confirmation pattern for location status (the pull-based half is
/// [CheckLocationEnabledUseCase], called on init and resume).
class WatchLocationStatusUseCase {
  /// Constructs the use case with its repository dependency.
  WatchLocationStatusUseCase(this._repository);

  final LocationRepository _repository;

  /// Executes the use case, returning the location-status stream.
  Stream<bool> call() => _repository.locationServiceStatus;
}
