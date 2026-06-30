/// Platform-channel naming and method constants.
///
/// These mirror the names registered on the Native Kotlin side. Keeping them
/// in one place guarantees the Flutter ↔ Native contract stays in sync and
/// makes the communication surface auditable at a glance.
class ChannelConstants {
  ChannelConstants._();

  // ── Channel names ────────────────────────────────────────────────────────

  /// MethodChannel used for request/response commands (lock, permission check).
  static const String methodChannelName = 'app_lock/methods';

  /// EventChannel used for push-based status updates from native to Flutter.
  static const String eventChannelName = 'app_lock/events';

  // ── Method names (MethodChannel) ───────────────────────────────────────────

  /// Check whether Device Owner / Profile Owner permission is currently active.
  static const String methodCheckPermission = 'checkPermission';

  /// Request Device Owner / Profile Owner permission (managed-provisioning).
  static const String methodRequestPermission = 'requestPermission';

  /// Query whether in-app Device Owner provisioning is allowed on this device.
  static const String methodIsProvisioningAllowed = 'isProvisioningAllowed';

  /// Send the lock command to the native lock engine.
  static const String methodLockNow = 'lockNow';

  /// Send the lock command with a custom lockout duration (ms).
  static const String methodLockForDuration = 'lockForDuration';

  /// Start the foreground background service.
  static const String methodStartService = 'startService';

  /// Stop the foreground background service.
  static const String methodStopService = 'stopService';

  /// Query whether the foreground service is currently running.
  static const String methodIsServiceRunning = 'isServiceRunning';

  /// Set the uninstall-block restriction on (`true`) or off (`false`).
  static const String methodSetUninstallBlocked = 'setUninstallBlocked';

  /// Query whether the uninstall-block restriction is currently active.
  static const String methodIsUninstallBlocked = 'isUninstallBlocked';

  /// Query whether true uninstall blocking is supported
  /// (Device/Profile Owner).
  static const String methodIsUninstallBlockSupported =
      'isUninstallBlockSupported';

  // ── Event types (EventChannel) ─────────────────────────────────────────────

  /// Emitted when the device-admin permission state changes.
  static const String eventPermissionChanged = 'permissionChanged';

  /// Emitted when a lock command completes (success or failure).
  static const String eventLockResult = 'lockResult';

  /// Emitted when the background service state changes.
  static const String eventServiceStateChanged = 'serviceStateChanged';

  /// Emitted when the post-lock lockout window opens or closes.
  static const String eventLockoutStateChanged = 'lockoutStateChanged';

  /// Emitted when the uninstall-block restriction is applied or removed.
  static const String eventUninstallBlockChanged = 'uninstallBlockChanged';

  // ── Event payload keys ─────────────────────────────────────────────────────

  /// Key under which the event `type` is published on every event map.
  static const String keyEventType = 'type';

  /// Key for the boolean permission state on a `permissionChanged` event.
  static const String keyGranted = 'granted';

  /// Key for the lock outcome string on a `lockResult` event.
  static const String keyResult = 'result';

  /// Key for the service status string on a `serviceStateChanged` event.
  static const String keyStatus = 'status';

  /// Key for the boolean lockout-active flag on a `lockoutStateChanged` event.
  static const String keyActive = 'active';

  /// Key for the boolean blocked flag on an `uninstallBlockChanged` event and
  /// the argument to the `setUninstallBlocked` method.
  static const String keyBlocked = 'blocked';

  /// Key for the lockout duration (ms) argument to `lockForDuration`.
  static const String keyDurationMs = 'durationMs';
}
