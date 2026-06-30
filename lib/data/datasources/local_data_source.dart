import 'package:get_storage/get_storage.dart';

import '../../core/constants/storage_keys.dart';
import '../../core/utils/app_logger.dart';

/// Data source for local key/value persistence via `get_storage`.
///
/// Stores demo flags and cached values. No database is used — this is the
/// only persistence mechanism in the app. Repository implementations read
/// and write through this data source.
class LocalDataSource {
  /// Constructs the local data source with its storage container.
  LocalDataSource(this._storage);

  final GetStorage _storage;

  // ── Welcome ──────────────────────────────────────────────────────────────

  /// Whether the user has already seen the Welcome screen.
  bool get hasSeenWelcome =>
      _storage.read<bool>(StorageKeys.hasSeenWelcome) ?? false;

  /// Persists the "has seen welcome" flag.
  Future<void> setHasSeenWelcome(bool value) =>
      _storage.write(StorageKeys.hasSeenWelcome, value);

  // ── Device admin ──────────────────────────────────────────────────────────

  /// Cached device-admin active state (re-validated on resume).
  bool get isDeviceAdminActive =>
      _storage.read<bool>(StorageKeys.isDeviceAdminActive) ?? false;

  /// Persists the cached device-admin active state.
  Future<void> setDeviceAdminActive(bool value) =>
      _storage.write(StorageKeys.isDeviceAdminActive, value);

  // ── Last lock time ────────────────────────────────────────────────────────

  /// ISO timestamp of the last successful lock, if any.
  String? get lastLockTime => _storage.read<String>(StorageKeys.lastLockTime);

  /// Persists the last lock timestamp.
  Future<void> setLastLockTime(String value) =>
      _storage.write(StorageKeys.lastLockTime, value);

  // ── Demo running ──────────────────────────────────────────────────────────

  /// Whether the demo is currently running.
  bool get isDemoRunning =>
      _storage.read<bool>(StorageKeys.isDemoRunning) ?? false;

  /// Persists the demo-running flag.
  Future<void> setDemoRunning(bool value) =>
      _storage.write(StorageKeys.isDemoRunning, value);

  /// Clears all persisted values (used on reset).
  Future<void> clearAll() async {
    await _storage.erase();
    AppLogger.info('Local storage cleared');
  }
}
