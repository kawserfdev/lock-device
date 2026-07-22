package com.example.app_lock.compliance

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.app_lock.admin.AppLockDeviceAdminReceiver

/**
 * Controls Lock Task Mode for the Location Compliance Enforcement System.
 *
 * Lock Task Mode pins a single app (or a set of allowlisted apps) to the
 * screen, hiding the recents/home buttons so the user cannot leave the
 * compliance screen until they enable location. This is the official Android
 * Enterprise API for kiosk-style restriction — it relies only on supported
 * Device Owner behavior, not on unsupported workarounds.
 *
 * ## How it works
 *
 * 1. **Allowlist** the app's package via
 *    [DevicePolicyManager.setLockTaskPackages]. Only a Device Owner can set the
 *    allowlist; the packages named here can then call
 *    [android.app.Activity.startLockTask] without the user-confirm dialog.
 * 2. **Start** Lock Task Mode from the compliance Activity via
 *    [android.app.Activity.startLockTask]. The Activity is pinned; the home /
 *    recents buttons are disabled (or hidden, depending on the feature flags).
 * 3. **Configure feature flags** via [DevicePolicyManager.setLockTaskFeatures]
 *    to leave only the minimum UI affordances the user needs to reach Location
 *    Settings (e.g. the status bar, so they can open Quick Settings to toggle
 *    location). The flags are tuned so the user can enable location but cannot
 *    escape to arbitrary apps.
 * 4. **Stop** Lock Task Mode via [android.app.Activity.stopLockTask] when
 *    location is restored.
 *
 * ## Capability
 *
 * All Lock Task operations require the Device Owner role. [isSupported]
 * checks Device Owner + admin active, mirroring
 * [com.example.app_lock.admin.LocationEnforcementController.isSupported].
 *
 * ## Android 12–15 notes
 *
 * `setLockTaskPackages`, `setLockTaskFeatures`, and `startLockTask` /
 * `stopLockTask` are stable across all API levels the app targets. No
 * deprecations affect this path. On Android 14+ the `FOREGROUND_SERVICE_SPECIAL_USE`
 * type (declared for the compliance service) does not interfere with Lock Task
 * Mode.
 *
 * ## Thread-safety
 *
 * The controller is stateless beyond the injected context and lazy system
 * service handles, so it is safe to construct on any thread. Callers (the
 * coordinator and the compliance Activity) serialize access through their own
 * `@Synchronized` guards.
 *
 * @param context Any context; the application context is used so the
 *   controller is not tied to an Activity lifecycle.
 */
class LockTaskController(context: Context) {

    companion object {
        private const val TAG = "LockTaskCtrl"

        /**
         * Lock Task feature flags applied while the compliance screen is pinned.
         *
         * - [DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO] — keeps the
         *   status bar visible so the user can open Quick Settings to toggle
         *   location (the fastest path to re-enable GPS).
         * - [DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS] — lets the
         *   user see (but not act on) notifications, so they are not confused
         *   by a blank status bar.
         *
         * Deliberately **excluded**:
         * - `LOCK_TASK_FEATURE_HOME` — would let the user leave the screen.
         * - `LOCK_TASK_FEATURE_OVERVIEW` — would let the user open recents.
         * - `LOCK_TASK_FEATURE_KEYGUARD` — would let the user reach the lock
         *   screen (the coordinator handles locking itself via `lockNow()`).
         */
        private val COMPLIANCE_FEATURES =
            DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS

        /**
         * Lock Task feature flags applied while the Status ("App Lock Time
         * counter") screen is pinned in kiosk mode.
         *
         * Every escape affordance is disabled so the user cannot leave the
         * screen via the system navigation buttons:
         * - `LOCK_TASK_FEATURE_HOME` — excluded (no Home button).
         * - `LOCK_TASK_FEATURE_OVERVIEW` — excluded (no Recents button).
         * - `LOCK_TASK_FEATURE_SYSTEM_INFO` — excluded (no status bar / Quick
         *   Settings; the Status screen needs no system escape).
         * - `LOCK_TASK_FEATURE_NOTIFICATIONS` — excluded (no notification tray).
         * - `LOCK_TASK_FEATURE_KEYGUARD` — excluded (no lock screen).
         *
         * `LOCK_TASK_FEATURE_NONE` leaves only the Back gesture, which the
         * Flutter side intercepts via `PopScope`.
         */
        val KIOSK_FEATURES = DevicePolicyManager.LOCK_TASK_FEATURE_NONE
    }

    private val appContext = context.applicationContext

    private val devicePolicyManager: DevicePolicyManager by lazy {
        appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private val adminComponent: ComponentName by lazy {
        AppLockDeviceAdminReceiver.componentName(appContext)
    }

    /**
     * Returns `true` when this app can manage Lock Task Mode.
     *
     * Requires the Device Owner role (for `setLockTaskPackages`) **and** an
     * active admin component.
     */
    fun isSupported(): Boolean {
        val adminActive = devicePolicyManager.isAdminActive(adminComponent)
        val deviceOwner = devicePolicyManager.isDeviceOwnerApp(appContext.packageName)
        val supported = adminActive && deviceOwner
        Log.d(
            TAG,
            "isSupported() -> adminActive=$adminActive, deviceOwner=$deviceOwner, supported=$supported",
        )
        return supported
    }

    /**
     * Allowlists this app's package for Lock Task Mode and applies the
     * compliance feature flags.
     *
     * Must be called **before** [android.app.Activity.startLockTask] is invoked
     * on the compliance Activity, otherwise `startLockTask` shows the
     * user-confirm dialog (which the user could dismiss). Idempotent — safe to
     * call repeatedly.
     *
     * @return `true` when the allowlist was set; `false` when not supported or
     *   the call threw.
     */
    fun arm(): Boolean = arm(COMPLIANCE_FEATURES)

    /**
     * Allowlists this app's package for Lock Task Mode and applies the given
     * [features] flag set.
     *
     * Overload used by callers that need a different feature configuration
     * than the compliance screen — e.g. the Status ("App Lock Time counter")
     * screen, which pins with [KIOSK_FEATURES] to disable every system
     * navigation affordance.
     *
     * Must be called **before** [android.app.Activity.startLockTask] is invoked
     * on the host Activity, otherwise `startLockTask` shows the user-confirm
     * dialog (which the user could dismiss). Idempotent — safe to call
     * repeatedly.
     *
     * @return `true` when the allowlist was set; `false` when not supported or
     *   the call threw.
     */
    fun arm(features: Int): Boolean {
        if (!isSupported()) {
            Log.w(TAG, "arm(features=$features) -> not supported (not Device Owner / admin inactive)")
            return false
        }
        return try {
            // Allowlist only this app — no other package can be pinned.
            devicePolicyManager.setLockTaskPackages(
                adminComponent,
                arrayOf(appContext.packageName),
            )
            // Apply the requested feature flags.
            devicePolicyManager.setLockTaskFeatures(adminComponent, features)
            Log.i(TAG, "arm(features=$features) — allowlist + features set")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "arm(features=$features) — setLockTaskPackages/features failed", t)
            false
        }
    }

    /**
     * Clears the Lock Task allowlist and resets the feature flags to "none".
     *
     * Called when the compliance loop ends (location restored) so the device
     * returns to normal usage. Idempotent.
     */
    fun disarm(): Boolean {
        if (!isSupported()) {
            Log.d(TAG, "disarm() -> not supported, no-op")
            return false
        }
        return try {
            devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf())
            devicePolicyManager.setLockTaskFeatures(
                adminComponent,
                DevicePolicyManager.LOCK_TASK_FEATURE_NONE,
            )
            Log.i(TAG, "disarm() — allowlist cleared, features reset to NONE")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "disarm() — failed", t)
            false
        }
    }

    /**
     * Returns `true` when this app's package is currently in the Lock Task
     * allowlist.
     *
     * Used by the compliance Activity to decide whether `startLockTask()` will
     * pin silently (allowlisted) or show the user-confirm dialog (not
     * allowlisted).
     */
    fun isAllowlisted(): Boolean {
        if (!isSupported()) return false
        return try {
            val packages = devicePolicyManager.getLockTaskPackages(adminComponent)
            packages.any { it == appContext.packageName }
        } catch (t: Throwable) {
            Log.w(TAG, "isAllowlisted() — getLockTaskPackages failed", t)
            false
        }
    }
}
