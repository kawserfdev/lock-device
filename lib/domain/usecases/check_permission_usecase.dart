import '../entities/app_state.dart';
import '../repositories/permission_repository.dart';

/// Use case: check the current device-admin permission status.
///
/// Thin orchestration layer that keeps controllers free of repository
/// knowledge. Each use case has a single responsibility, making the domain
/// behaviour easy to test and reason about.
class CheckPermissionUseCase {
  /// Constructs the use case with its repository dependency.
  CheckPermissionUseCase(this._repository);

  final PermissionRepository _repository;

  /// Executes the use case, returning the current permission status.
  Future<PermissionStatus> call() => _repository.checkPermission();
}
