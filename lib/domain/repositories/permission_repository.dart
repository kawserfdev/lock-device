import 'dart:async';

import '../entities/app_state.dart';

/// Abstract port for Device Owner / Profile Owner permission operations.
///
/// Declared in the domain layer and implemented by the data layer. Use cases
/// and controllers depend on this abstraction, never on a concrete data
/// source — preserving the dependency rule of Clean Architecture.
abstract class PermissionRepository {
  /// Returns the current Device Owner / Profile Owner permission status.
  Future<PermissionStatus> checkPermission();

  /// Requests Device Owner permission (launches managed-provisioning).
  ///
  /// Returns the resulting status after the user interacts with the system
  /// screen. Because this is a special-access permission, the outcome is
  /// re-validated rather than trusted from the request itself.
  Future<PermissionStatus> requestPermission();

  /// Returns `true` when in-app Device Owner provisioning is allowed.
  ///
  /// Managed-provisioning only succeeds on a device with no user accounts.
  /// When `false`, the user must use `adb shell dpm set-device-owner` instead.
  Future<bool> isProvisioningAllowed();

  /// Stream of permission-state changes pushed from native.
  Stream<PermissionStatus> get permissionChanges;
}
