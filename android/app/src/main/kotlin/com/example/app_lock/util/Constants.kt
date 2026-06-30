package com.example.app_lock.util

/**
 * Single source of truth for the Flutter ↔ Native contract on the Kotlin side.
 *
 * These values MUST stay in sync with the Dart constants declared in
 * `lib/core/constants/channel_constants.dart`. Keeping them in one auditable
 * place prevents the most common platform-channel bug: a string mismatch that
 * silently drops a method call or event.
 *
 * No business logic lives here — only identifiers and static demo constants.
 */
object Constants {

    // ── Channel names ────────────────────────────────────────────────────────

    /** MethodChannel used for request/response commands (lock, permission check). */
    const val METHOD_CHANNEL_NAME = "app_lock/methods"

    /** EventChannel used for push-based status updates from native to Flutter. */
    const val EVENT_CHANNEL_NAME = "app_lock/events"

    // ── Method names (MethodChannel) ─────────────────────────────────────────

    /** Check whether Device Owner / Profile Owner permission is currently active. */
    const val METHOD_CHECK_PERMISSION = "checkPermission"

    /** Request Device Owner / Profile Owner permission (managed-provisioning). */
    const val METHOD_REQUEST_PERMISSION = "requestPermission"

    /** Query whether in-app Device Owner / Profile Owner provisioning is allowed. */
    const val METHOD_IS_PROVISIONING_ALLOWED = "isProvisioningAllowed"

    /** Send the lock command to the native lock engine. */
    const val METHOD_LOCK_NOW = "lockNow"

    /** Send the lock command with a custom lockout duration (ms). */
    const val METHOD_LOCK_FOR_DURATION = "lockForDuration"

    /** Start the foreground background service. */
    const val METHOD_START_SERVICE = "startService"

    /** Stop the foreground background service. */
    const val METHOD_STOP_SERVICE = "stopService"

    /** Query whether the foreground service is currently running. */
    const val METHOD_IS_SERVICE_RUNNING = "isServiceRunning"

    /** Set the uninstall-block restriction on (`true`) or off (`false`). */
    const val METHOD_SET_UNINSTALL_BLOCKED = "setUninstallBlocked"

    /** Query whether the uninstall-block restriction is currently active. */
    const val METHOD_IS_UNINSTALL_BLOCKED = "isUninstallBlocked"

    /** Query whether true uninstall blocking is supported (Device/Profile Owner). */
    const val METHOD_IS_UNINSTALL_BLOCK_SUPPORTED = "isUninstallBlockSupported"

    // ── Event types (EventChannel) ───────────────────────────────────────────

    /** Emitted when the device-admin permission state changes. */
    const val EVENT_PERMISSION_CHANGED = "permissionChanged"

    /** Emitted when a lock command completes (success or failure). */
    const val EVENT_LOCK_RESULT = "lockResult"

    /** Emitted when the background service state changes. */
    const val EVENT_SERVICE_STATE_CHANGED = "serviceStateChanged"

    /** Emitted when the post-lock lockout window opens or closes. */
    const val EVENT_LOCKOUT_STATE_CHANGED = "lockoutStateChanged"

    /** Emitted when the uninstall-block restriction is applied or removed. */
    const val EVENT_UNINSTALL_BLOCK_CHANGED = "uninstallBlockChanged"

    // ── Event payload keys ───────────────────────────────────────────────────

    /** Key under which the event `type` is published on every event map. */
    const val KEY_EVENT_TYPE = "type"

    /** Key for the boolean permission state on a `permissionChanged` event. */
    const val KEY_GRANTED = "granted"

    /** Key for the lock outcome string on a `lockResult` event. */
    const val KEY_RESULT = "result"

    /** Key for the service status string on a `serviceStateChanged` event. */
    const val KEY_STATUS = "status"

    /** Key for the boolean lockout-active flag on a `lockoutStateChanged` event. */
    const val KEY_ACTIVE = "active"

    /** Key for the boolean blocked flag on an `uninstallBlockChanged` event and
     *  the argument to the `setUninstallBlocked` method. */
    const val KEY_BLOCKED = "blocked"

    /** Key for the lockout duration (ms) argument to `lockForDuration`. */
    const val KEY_DURATION_MS = "durationMs"

    // ── Lock result enum strings (mirrors Dart `LockResult`) ─────────────────

    const val LOCK_RESULT_SUCCESS = "success"
    const val LOCK_RESULT_ALREADY_LOCKED = "already_locked"
    const val LOCK_RESULT_PERMISSION_MISSING = "permission_missing"
    const val LOCK_RESULT_FAILED = "failed"

    // ── Service status enum strings (mirrors Dart `ServiceStatus`) ──────────

    const val SERVICE_STATUS_RUNNING = "running"
    const val SERVICE_STATUS_TRANSITIONING = "transitioning"
    const val SERVICE_STATUS_STOPPED = "stopped"

    // ── Lockout state enum strings (mirrors Dart `LockoutState`) ─────────────

    /** Lockout window is open — unlock attempts are immediately re-locked. */
    const val LOCKOUT_STATE_ACTIVE = "active"

    /** Lockout window has elapsed — the user may unlock normally. */
    const val LOCKOUT_STATE_INACTIVE = "inactive"

    // ── Static demo constants (no backend, no DB) ───────────────────────────

    /** Delay in milliseconds before the foreground service triggers a lock. */
    const val LOCK_DELAY_MS = 30_000L

    /**
     * Static overdue flag that drives the lock decision.
     *
     * In a real product this would come from the backend; for the demo it is a
     * compile-time constant so the whole flow is reproducible offline.
     */
    const val OVERDUE = true

    /**
     * Duration in milliseconds during which the device cannot be unlocked after
     * a successful lock.
     *
     * Any `ACTION_USER_PRESENT` broadcast (user unlocked) arriving within this
     * window triggers an immediate re-lock via the native lock engine. After the
     * window elapses the lockout is cleared and the user may unlock normally.
     */
    const val LOCKOUT_DURATION_MS = 60_000L

    // ── Foreground service ───────────────────────────────────────────────────

    /** Notification channel id for the foreground service notification. */
    const val NOTIFICATION_CHANNEL_ID = "app_lock_service_channel"

    /** Notification channel user-visible name. */
    const val NOTIFICATION_CHANNEL_NAME = "App Lock Service"

    /** Notification id used by the foreground service (must be > 0). */
    const val FOREGROUND_NOTIFICATION_ID = 1001

    // ── Device Admin ──────────────────────────────────────────────────────────

    /** Component name qualifier used to identify our DeviceAdminReceiver. */
    const val DEVICE_ADMIN_COMPONENT_QUALIFIER = "app_lock_admin"
}
