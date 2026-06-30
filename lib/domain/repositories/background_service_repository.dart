import '../entities/app_state.dart';

/// Abstract port for the background foreground-service.
///
/// Implemented by the data layer. The domain controls the service lifecycle
/// (start/stop/status) through this port without knowing how the native side
/// realises it.
abstract class BackgroundServiceRepository {
  /// Starts the foreground background service.
  ///
  /// Returns `true` if the service started successfully.
  Future<bool> startService();

  /// Stops the foreground background service.
  ///
  /// Returns `true` if the service stopped successfully.
  Future<bool> stopService();

  /// Returns whether the service is currently running.
  Future<bool> isServiceRunning();

  /// Stream of service-state changes pushed from native.
  Stream<ServiceStatus> get serviceStateChanges;
}
