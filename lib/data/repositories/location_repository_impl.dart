import 'dart:async';

import 'package:geolocator/geolocator.dart';

import '../../core/utils/app_logger.dart';
import '../../domain/repositories/location_repository.dart';

/// Implementation of [LocationRepository] backed by the `geolocator` package.
///
/// This is the only place in the data layer that imports `geolocator`. It
/// translates the plugin's [ServiceStatus] enum into a plain `bool` so the
/// domain layer (repository abstraction, use cases, controllers) never depends
/// on the plugin's types — keeping the dependency rule of Clean Architecture
/// intact.
///
/// ## Why a separate repository (not NativeDataSource)
///
/// `NativeDataSource` talks to the app's *own* platform channels
/// (`app_lock/methods`, `app_lock/events`). Location status comes from the
/// `geolocator` plugin's *own* internal channels, so it does not belong in
/// `NativeDataSource`. A dedicated repository keeps the two channel families
/// cleanly separated.
class LocationRepositoryImpl implements LocationRepository {
  /// Constructs the location repository.
  ///
  /// No dependencies are injected because `geolocator` exposes a static facade
  /// (`Geolocator.isLocationServiceEnabled()`, etc.). The class is still
  /// constructed via [DependencyInjection] so it can be swapped for a fake in
  /// tests.
  LocationRepositoryImpl();

  @override
  Future<bool> isLocationServiceEnabled() async {
    try {
      final bool enabled = await Geolocator.isLocationServiceEnabled();
      AppLogger.debug('Location service enabled: $enabled');
      return enabled;
    } catch (e, st) {
      AppLogger.error(
        'isLocationServiceEnabled failed',
        error: e,
        stackTrace: st,
      );
      return false;
    }
  }

  @override
  Stream<bool> get locationServiceStatus {
    // Geolocator's stream emits ServiceStatus.enabled / .disabled. We map to
    // a plain bool so the domain layer has no geolocator type dependency.
    // Errors are logged and mapped to `false` so the UI degrades gracefully
    // (button stays disabled) rather than crashing.
    return Geolocator.getServiceStatusStream()
        .map((ServiceStatus status) => status == ServiceStatus.enabled)
        .handleError((Object e, StackTrace st) {
      AppLogger.error('locationServiceStatus stream error',
          error: e, stackTrace: st);
    });
  }

  @override
  Future<bool> openLocationSettings() async {
    try {
      final bool opened = await Geolocator.openLocationSettings();
      AppLogger.debug('openLocationSettings result: $opened');
      return opened;
    } catch (e, st) {
      AppLogger.error('openLocationSettings failed', error: e, stackTrace: st);
      return false;
    }
  }
}
