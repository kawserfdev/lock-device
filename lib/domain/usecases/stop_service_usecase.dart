import '../repositories/background_service_repository.dart';

/// Use case: stop the background foreground-service.
///
/// Cancels the pending lock trigger if the timer has not yet fired.
class StopServiceUseCase {
  /// Constructs the use case with its repository dependency.
  StopServiceUseCase(this._repository);

  final BackgroundServiceRepository _repository;

  /// Executes the use case, returning `true` on success.
  Future<bool> call() => _repository.stopService();
}
