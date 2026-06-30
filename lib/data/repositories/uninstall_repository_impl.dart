import '../../core/errors/app_error.dart';
import '../../core/utils/app_logger.dart';
import '../../domain/repositories/uninstall_repository.dart';
import '../datasources/native_data_source.dart';

/// Implementation of [UninstallRepository].
///
/// Delegates to the native data source and converts platform errors into
/// honest boolean outcomes so the UI never crashes on a channel failure.
class UninstallRepositoryImpl implements UninstallRepository {
  /// Constructs the repository with its data-source dependency.
  UninstallRepositoryImpl({
    required NativeDataSource nativeDataSource,
  }) : _native = nativeDataSource;

  final NativeDataSource _native;

  @override
  Future<bool> setUninstallBlocked(bool blocked) async {
    try {
      final bool applied = await _native.setUninstallBlocked(blocked);
      AppLogger.info(
        'setUninstallBlocked($blocked) -> applied=$applied',
      );
      return applied;
    } on AppError catch (e) {
      AppLogger.error('setUninstallBlocked($blocked) failed', error: e);
      return false;
    }
  }

  @override
  Future<bool> isUninstallBlocked() async {
    try {
      return await _native.isUninstallBlocked();
    } on AppError catch (e) {
      AppLogger.error('isUninstallBlocked failed', error: e);
      return false;
    }
  }

  @override
  Future<bool> isUninstallBlockSupported() async {
    try {
      return await _native.isUninstallBlockSupported();
    } on AppError catch (e) {
      AppLogger.error('isUninstallBlockSupported failed', error: e);
      return false;
    }
  }

  @override
  Stream<bool> get uninstallBlockChanges => _native.uninstallBlockChanges;
}
