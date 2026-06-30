import 'dart:async';

import '../../core/errors/app_error.dart';
import '../../core/utils/app_logger.dart';
import '../../domain/entities/app_state.dart';
import '../../domain/repositories/lock_repository.dart';
import '../datasources/local_data_source.dart';
import '../datasources/native_data_source.dart';

/// Implementation of [LockRepository].
///
/// Sends the lock command through the native data source and records the
/// timestamp of a successful lock in the local data source.
class LockRepositoryImpl implements LockRepository {
  /// Constructs the repository with its data-source dependencies.
  LockRepositoryImpl({
    required NativeDataSource nativeDataSource,
    required LocalDataSource localDataSource,
  })  : _native = nativeDataSource,
        _local = localDataSource;

  final NativeDataSource _native;
  final LocalDataSource _local;

  @override
  Future<LockResult> lockNow() async {
    try {
      final bool success = await _native.lockNow();
      if (success) {
        final String timestamp = DateTime.now().toUtc().toIso8601String();
        await _local.setLastLockTime(timestamp);
        AppLogger.info('Lock command succeeded at $timestamp');
        return LockResult.success;
      }
      AppLogger.warning('Lock command returned false');
      return LockResult.failed;
    } on AppError catch (e) {
      AppLogger.error('lockNow failed', error: e);
      return LockResult.failed;
    }
  }

  @override
  Future<LockResult> lockForDuration(int durationMs) async {
    try {
      final bool success = await _native.lockForDuration(durationMs);
      if (success) {
        final String timestamp = DateTime.now().toUtc().toIso8601String();
        await _local.setLastLockTime(timestamp);
        AppLogger.info(
          'Timed lock command succeeded at $timestamp '
          '(duration=${durationMs}ms)',
        );
        return LockResult.success;
      }
      AppLogger.warning('Timed lock command returned false');
      return LockResult.failed;
    } on AppError catch (e) {
      AppLogger.error('lockForDuration failed', error: e);
      return LockResult.failed;
    }
  }

  @override
  Stream<LockResult> get lockResults => _native.lockResults;

  @override
  Stream<bool> get lockoutStateChanges => _native.lockoutStateChanges;
}
