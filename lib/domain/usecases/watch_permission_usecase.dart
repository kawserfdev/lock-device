import '../entities/app_state.dart';
import '../repositories/permission_repository.dart';

/// Use case: observe device-admin permission state changes pushed from native.
///
/// Returns the live [Stream] of [PermissionStatus] events emitted by the
/// native `DeviceAdminReceiver` callbacks (enabled/disabled). This is the
/// push-based half of the dual-confirmation pattern (architecture review
/// §5.1); the pull-based half is [CheckPermissionUseCase] called on resume.
class WatchPermissionUseCase {
  /// Constructs the use case with its repository dependency.
  WatchPermissionUseCase(this._repository);

  final PermissionRepository _repository;

  /// Executes the use case, returning the permission-status stream.
  Stream<PermissionStatus> call() => _repository.permissionChanges;
}
