/// Centralised key names for `get_storage` persistence.
///
/// Using a single enum-like class prevents key-typo bugs and makes it trivial
/// to audit every persisted value in the demo. No database is used — these
/// keys map to lightweight key/value entries only.
class StorageKeys {
  StorageKeys._();

  /// Whether the user has already seen the Welcome screen.
  static const String hasSeenWelcome = 'has_seen_welcome';

  /// Cached device-admin active state (re-validated on resume).
  static const String isDeviceAdminActive = 'is_device_admin_active';

  /// ISO timestamp of the last successful lock command.
  static const String lastLockTime = 'last_lock_time';

  /// Whether the demo is currently running.
  static const String isDemoRunning = 'is_demo_running';

  /// Serialized user model JSON string.
  static const String userModel = 'user_model';
}

