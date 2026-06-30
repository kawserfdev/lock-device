import 'dart:async';

import '../../core/errors/app_error.dart';
import '../../core/utils/app_logger.dart';
import '../../domain/entities/app_state.dart';
import '../../domain/repositories/background_service_repository.dart';
import '../datasources/native_data_source.dart';

/// Implementation of [BackgroundServiceRepository].
///
/// Delegates start/stop/status to the native data source and exposes the
/// native service-state change stream.
class BackgroundServiceRepositoryImpl implements BackgroundServiceRepository {
  /// Constructs the repository with its native data-source dependency.
  BackgroundServiceRepositoryImpl(this._native);

  final NativeDataSource _native;

  @override
  Future<bool> startService() async {
    try {
      final bool ok = await _native.startService();
      AppLogger.debug('startService -> $ok');
      return ok;
    } on AppError catch (e) {
      AppLogger.error('startService failed', error: e);
      return false;
    }
  }

  @override
  Future<bool> stopService() async {
    try {
      final bool ok = await _native.stopService();
      AppLogger.debug('stopService -> $ok');
      return ok;
    } on AppError catch (e) {
      AppLogger.error('stopService failed', error: e);
      return false;
    }
  }

  @override
  Future<bool> isServiceRunning() async {
    try {
      return _native.isServiceRunning();
    } on AppError catch (e) {
      AppLogger.error('isServiceRunning failed', error: e);
      return false;
    }
  }

  @override
  Stream<ServiceStatus> get serviceStateChanges =>
      _native.serviceStateChanges;
}
