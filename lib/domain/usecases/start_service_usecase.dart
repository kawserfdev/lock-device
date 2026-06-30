import '../repositories/background_service_repository.dart';

/// Use case: start the background foreground-service.
///
/// The service begins a timer that, after the predefined delay, triggers the
/// lock command. This use case only starts the service; the lock trigger
/// itself is handled by the service on the native side.
class StartServiceUseCase {
  /// Constructs the use case with its repository dependency.
  StartServiceUseCase(this._repository);

  final BackgroundServiceRepository _repository;

  /// Executes the use case, returning `true` on success.
  Future<bool> call() => _repository.startService();
}
