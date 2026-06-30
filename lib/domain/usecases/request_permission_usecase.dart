import '../entities/app_state.dart';
import '../repositories/permission_repository.dart';

/// Use case: request device-admin permission.
///
/// Delegates to the permission repository. The repository is responsible for
/// re-validating the outcome after the system permission screen closes,
/// because device-admin is a special-access permission where request ≠
/// outcome.
class RequestPermissionUseCase {
  /// Constructs the use case with its repository dependency.
  RequestPermissionUseCase(this._repository);

  final PermissionRepository _repository;

  /// Executes the use case, returning the resulting permission status.
  Future<PermissionStatus> call() => _repository.requestPermission();
}
