import 'dart:async';

import '../../core/errors/app_error.dart';
import '../../core/utils/app_logger.dart';
import '../../domain/entities/app_state.dart';
import '../../domain/repositories/permission_repository.dart';
import '../datasources/local_data_source.dart';
import '../datasources/native_data_source.dart';

/// Implementation of [PermissionRepository].
///
/// Aggregates the native data source (live permission state) and the local
/// data source (cached state). Because device-admin is a special-access
/// permission, the outcome of a request is re-validated rather than trusted
/// from the request itself.
class PermissionRepositoryImpl implements PermissionRepository {
  /// Constructs the repository with its data-source dependencies.
  PermissionRepositoryImpl({
    required NativeDataSource nativeDataSource,
    required LocalDataSource localDataSource,
  })  : _native = nativeDataSource,
        _local = localDataSource;

  final NativeDataSource _native;
  final LocalDataSource _local;

  @override
  Future<PermissionStatus> checkPermission() async {
    try {
      final bool granted = await _native.checkPermission();
      final PermissionStatus status = granted
          ? PermissionStatus.granted
          : PermissionStatus.denied;
      await _local.setDeviceAdminActive(granted);
      AppLogger.debug('Permission checked: $status');
      return status;
    } on AppError catch (e) {
      AppLogger.error('checkPermission failed', error: e);
      return PermissionStatus.unknown;
    }
  }

  @override
  Future<PermissionStatus> requestPermission() async {
    try {
      // Fire the request to launch the managed-provisioning screen. The
      // native call returns as soon as the system screen is *launched*, NOT
      // once the user has acted — so the actual outcome is unknown at this
      // point. The status is later resolved by:
      //   1. the `permissionChanges` stream (pushed from the native
      //      DeviceAdminReceiver callbacks), and
      //   2. a resume-time re-check in the controller (dual-confirmation,
      //      architecture review §5.1).
      await _native.requestPermission();
      return PermissionStatus.unknown;
    } on AppError catch (e) {
      AppLogger.error('requestPermission failed', error: e);
      return PermissionStatus.denied;
    }
  }

  @override
  Future<bool> isProvisioningAllowed() {
    try {
      return _native.isProvisioningAllowed();
    } on AppError catch (e) {
      AppLogger.error('isProvisioningAllowed failed', error: e);
      return Future<bool>.value(false);
    }
  }

  @override
  Stream<PermissionStatus> get permissionChanges =>
      _native.permissionChanges;
}
