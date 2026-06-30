package com.example.app_lock.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.example.app_lock.channels.LockEventChannel

/**
 * Controls the uninstall-block restriction for the App Lock demo.
 *
 * Android only provides a *true* "cannot uninstall in any way" guarantee through
 * [DevicePolicyManager.addUserRestriction] with
 * [UserManager.DISALLOW_UNINSTALL_APPS]. That API requires the calling app to
 * be a **Device Owner** or **Profile Owner** — a plain Device Admin cannot set
 * user restrictions. This controller therefore:
 *
 * 1. Detects capability via [DevicePolicyManager.isDeviceOwnerApp] /
 *    [DevicePolicyManager.isProfileOwnerApp] and reports it honestly to
 *    Flutter so the UI can show whether the feature is available on this device.
 * 2. When supported, applies / removes the restriction and emits an
 *    `uninstallBlockChanged` event so Flutter's push-path state sync stays in
 *    step (architecture review §16.3).
 * 3. Tracks the intended state in a volatile field so the secondary
 *    [AppLockDeviceAdminReceiver.onDisableRequested] warning layer can show a
 *    contextual message even when the true restriction is not in force.
 *
 * Construction mirrors [DeviceAdminController]: MainActivity injects the live
 * [eventChannel]; the system-instantiated receiver falls back to the static
 * [LockEventChannel.instance] holder.
 */
class UninstallBlockController(
    private val context: Context,
    private val eventChannel: LockEventChannel? = null,
) {

    companion object {
        private const val TAG = "UninstallBlockCtrl"

        /**
         * Process-wide record of the *intended* uninstall-block state.
         *
         * Set whenever [setBlocked] runs. Read by
         * [AppLockDeviceAdminReceiver.onDisableRequested] so the secondary
         * warning layer can show a contextual message when the user tries to
         * disable the admin (which is the only uninstall path when the true
         * restriction is not in force).
         *
         * Volatile because it is written on the platform thread (MethodChannel
         * call) but read on the system's DeviceAdminReceiver callback thread.
         */
        @Volatile
        @JvmStatic
        var blockIntended: Boolean = false
    }

    private val devicePolicyManager: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private val adminComponent: ComponentName by lazy {
        AppLockDeviceAdminReceiver.componentName(context)
    }

    /**
     * Returns `true` when this app can truly block uninstallation.
     *
     * True blocking requires Device Owner or Profile Owner. A plain Device Admin
     * cannot set user restrictions, so on a non-provisioned device this returns
     * `false` and the UI reports the feature as unavailable.
     */
    fun isSupported(): Boolean {
        val deviceOwner = devicePolicyManager.isDeviceOwnerApp(context.packageName)
        val profileOwner = devicePolicyManager.isProfileOwnerApp(context.packageName)
        val supported = deviceOwner || profileOwner
        Log.d(TAG, "isSupported() -> deviceOwner=$deviceOwner, profileOwner=$profileOwner, supported=$supported")
        return supported
    }

    /**
     * Returns `true` when the uninstall-block restriction is currently in force.
     *
     * Reads the live user restriction from [DevicePolicyManager]. When the
     * restriction is not supported (non-owner), this always returns `false`.
     */
    fun isBlocked(): Boolean {
        if (!isSupported()) {
            return false
        }
        val blocked = devicePolicyManager.getUserRestrictions(adminComponent)
            ?.getBoolean(UserManager.DISALLOW_UNINSTALL_APPS) == true
        Log.d(TAG, "isBlocked() -> $blocked")
        return blocked
    }

    /**
     * Applies or removes the uninstall-block restriction.
     *
     * - When [blocked] is `true` and the app is an owner, applies
     *   [UserManager.DISALLOW_UNINSTALL_APPS]. The user can then no longer
     *   uninstall the app in any way (Settings, package installer, ADB shell
     *   `pm uninstall` against the user).
     * - When [blocked] is `false`, removes the restriction so the user may
     *   uninstall normally.
     *
     * The intended state is always recorded in [blockIntended] so the secondary
     * warning layer works regardless of owner status.
     *
     * @return `true` when the restriction was actually applied/removed; `false`
     * when the app is not an owner (the restriction cannot be set) or the call
     * threw.
     */
    fun setBlocked(blocked: Boolean): Boolean {
        Log.d(TAG, "setBlocked($blocked) invoked")

        // Always record intent so the warning layer is contextual.
        blockIntended = blocked

        if (!isSupported()) {
            Log.w(TAG, "setBlocked($blocked) -> not supported (not Device/Profile Owner)")
            // Still emit so Flutter's state reflects the intended toggle even
            // when the true restriction is unavailable — the UI will show the
            // "unsupported" capability separately.
            emitUninstallBlockChanged(blocked)
            return false
        }

        return try {
            if (blocked) {
                // addUserRestriction is available since API 8.
                devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)
                Log.i(TAG, "Uninstall block APPLIED")
            } else {
                // clearUserRestriction was added in API 24 (N). On older
                // devices the restriction cannot be removed via this API, so we
                // report the unchanged state honestly instead of crashing.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)
                    Log.i(TAG, "Uninstall block REMOVED")
                } else {
                    Log.w(TAG, "clearUserRestriction unavailable on API ${Build.VERSION.SDK_INT} (< N); block remains")
                    emitUninstallBlockChanged(true)
                    return false
                }
            }
            emitUninstallBlockChanged(blocked)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "setBlocked($blocked) failed", t)
            // Emit the actual (unchanged) state so Flutter does not desync.
            emitUninstallBlockChanged(isBlocked())
            false
        }
    }

    /**
     * Emits an `uninstallBlockChanged` event through whichever channel is
     * available.
     *
     * Prefers the injected [eventChannel]; falls back to the static
     * [LockEventChannel.instance] holder. When neither is set, the call is a
     * no-op — matching invariant §9.2.
     */
    private fun emitUninstallBlockChanged(blocked: Boolean) {
        val channel = eventChannel ?: LockEventChannel.instance
        channel?.emitUninstallBlockChanged(blocked)
    }
}
