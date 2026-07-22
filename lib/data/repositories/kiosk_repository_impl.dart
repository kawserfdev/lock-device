import '../../core/errors/app_error.dart';
import '../../core/utils/app_logger.dart';
import '../../domain/repositories/kiosk_repository.dart';
import '../datasources/native_data_source.dart';

/// Implementation of [KioskRepository].
///
/// Delegates to the native data source and converts platform errors into
/// honest boolean outcomes so the UI never crashes on a channel failure.
class KioskRepositoryImpl implements KioskRepository {
  /// Constructs the repository with its data-source dependency.
  KioskRepositoryImpl({
    required NativeDataSource nativeDataSource,
  }) : _native = nativeDataSource;

  final NativeDataSource _native;

  @override
  Future<bool> enableKioskMode() async {
    try {
      final bool enabled = await _native.enableKioskMode();
      AppLogger.info('enableKioskMode() -> enabled=$enabled');
      return enabled;
    } on AppError catch (e) {
      AppLogger.error('enableKioskMode failed', error: e);
      return false;
    }
  }

  @override
  Future<bool> disableKioskMode() async {
    try {
      final bool disabled = await _native.disableKioskMode();
      AppLogger.info('disableKioskMode() -> disabled=$disabled');
      return disabled;
    } on AppError catch (e) {
      AppLogger.error('disableKioskMode failed', error: e);
      return false;
    }
  }
}
