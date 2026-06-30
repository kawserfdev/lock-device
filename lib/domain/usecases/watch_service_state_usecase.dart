import '../entities/app_state.dart';
import '../repositories/background_service_repository.dart';

/// Use case: observe background service-state changes pushed from native.
///
/// Returns the live [Stream] of [ServiceStatus] events emitted by the native
/// foreground service (running / stopped). This is the push-based half of the
/// dual-path state synchronisation (architecture review §16.3); the pull-based
/// half is [StartServiceUseCase] / [StopServiceUseCase] returning a result.
///
/// The stream lets the Status UI update the moment the service enters its
/// active phase or stops — without the controller having to poll.
class WatchServiceStateUseCase {
  /// Constructs the use case with its repository dependency.
  WatchServiceStateUseCase(this._repository);

  final BackgroundServiceRepository _repository;

  /// Executes the use case, returning the service-status stream.
  Stream<ServiceStatus> call() => _repository.serviceStateChanges;
}
