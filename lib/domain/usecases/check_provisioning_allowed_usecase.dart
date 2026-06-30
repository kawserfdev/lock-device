import '../repositories/permission_repository.dart';

/// Use case: check whether in-app Device Owner provisioning is allowed.
///
/// Returns `true` only on a device with no user accounts (factory-fresh),
/// where the managed-provisioning intent can succeed. When `false`, the user
/// must use `adb shell dpm set-device-owner` instead — which works in both
/// debug and release builds.
class CheckProvisioningAllowedUseCase {
  /// Constructs the use case with its repository dependency.
  CheckProvisioningAllowedUseCase(this._repository);

  final PermissionRepository _repository;

  /// Executes the use case, returning whether provisioning is allowed.
  Future<bool> call() => _repository.isProvisioningAllowed();
}
