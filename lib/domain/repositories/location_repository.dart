import 'dart:async';

/// Abstract port for device location (GPS) service operations.
///
/// Declared in the domain layer and implemented by the data layer (backed by
/// the `geolocator` package). Use cases and controllers depend on this
/// abstraction, never on the plugin directly — preserving the dependency rule
/// of Clean Architecture and keeping the domain free of third-party packages.
///
/// The contract is intentionally boolean: location is either enabled or
/// disabled at the system level. The implementation translates the plugin's
/// [ServiceStatus] enum into a plain `bool` so the domain layer has no
/// dependency on `geolocator`'s types.
abstract class LocationRepository {
  /// Returns `true` when the device location service is currently enabled.
  Future<bool> isLocationServiceEnabled();

  /// Push stream of location-service on/off changes.
  ///
  /// Emits `true` when location becomes enabled and `false` when disabled.
  /// This is the push-based half of the dual-confirmation pattern (the
  /// pull-based half is [isLocationServiceEnabled], called on init and resume).
  Stream<bool> get locationServiceStatus;

  /// Opens the system location settings screen so the user can toggle GPS.
  ///
  /// Returns `true` when the settings screen was successfully launched.
  Future<bool> openLocationSettings();
}
