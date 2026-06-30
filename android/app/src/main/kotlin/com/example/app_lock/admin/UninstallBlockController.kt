package com.example.app_lock.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.UserManager
import android.util.Log
import com.example.app_lock.channels.LockEventChannel
import com.example.app_lock.permissions.ActivityLauncher

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
    private val activityLauncher: ActivityLauncher? = null,
) {

    companion object {
        private const val TAG = "UninstallBlockCtrl"

        /**
         * User restriction that blocks "Reset app preferences" in Settings.
         *
         * This is the `@hide` `UserManager.DISALLOW_CONFIG_APPS` constant —
         * not exposed in the public SDK, so the raw string value is used
         * directly. The value is stable and documented in the Android
         * source (`frameworks/base`).
         */
        private const val DISALLOW_CONFIG_APPS = "no_config_apps"

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
     * - When [blocked] is `false`, clears the Device Owner role (one-way)
     *   so the app becomes uninstallable, then launches the system uninstall
     *   dialog. After this the screen-lock feature is disabled until adb
     *   re-provisioning.
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
                // Apply the full protection bundle. All three restrictions
                // are Device-Owner user restrictions available since API 8.
                //
                // - DISALLOW_UNINSTALL_APPS: the user cannot uninstall this
                //   (or any) app in any way.
                // - DISALLOW_FACTORY_RESET: blocks "Erase all data (factory
                //   reset)" in Settings → System → Reset options.
                // - "no_config_apps" (DISALLOW_CONFIG_APPS): blocks "Reset
                //   app preferences" in Settings → Apps → menu, which would
                //   otherwise clear defaults/disabled-app state and weaken
                //   the protection. This constant is a @hide API, so the
                //   raw string value is used directly — it is stable and
                //   documented in the Android source.
                devicePolicyManager.addUserRestriction(
                    adminComponent,
                    UserManager.DISALLOW_UNINSTALL_APPS,
                )
                devicePolicyManager.addUserRestriction(
                    adminComponent,
                    UserManager.DISALLOW_FACTORY_RESET,
                )
                devicePolicyManager.addUserRestriction(
                    adminComponent,
                    DISALLOW_CONFIG_APPS,
                )
                Log.i(TAG, "Protection bundle APPLIED (uninstall/factory-reset/app-prefs)")
                emitUninstallBlockChanged(true)
                true
            } else {
                // Turning the block OFF is a destructive, one-way
                // operation: clear the Device Owner role (which also removes
                // all policies the owner set, including
                // DISALLOW_UNINSTALL_APPS) so the app becomes uninstallable,
                // then launch the system uninstall dialog for the user to
                // confirm.
                //
                // After this the app is no longer the Device Owner — the
                // screen-lock feature is disabled until adb re-provisioning.
                devicePolicyManager.clearDeviceOwnerApp(context.packageName)
                Log.i(TAG, "Device Owner CLEARED — app is now uninstallable")

                // Notify Flutter that both the permission and the block are
                // gone so the UI reconciles immediately.
                emitPermissionChanged(false)
                emitUninstallBlockChanged(false)

                // Launch the system "Uninstall this app?" dialog.
                launchUninstallDialog()
                true
            }
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

    /**
     * Emits a `permissionChanged` event through whichever channel is
     * available.
     *
     * Called after [setBlocked] clears the Device Owner role so Flutter's
     * permission status reconciles immediately.
     */
    private fun emitPermissionChanged(granted: Boolean) {
        val channel = eventChannel ?: LockEventChannel.instance
        channel?.emitPermissionChanged(granted)
    }

    /**
     * Launches the system "Uninstall this app?" dialog.
     *
     * Uses [Intent.ACTION_DELETE] with a `package:` URI so the platform
     * package installer shows the standard uninstall confirmation. If no
     * [ActivityLauncher] was injected, the call is a no-op and the user
     * must uninstall manually from Settings.
     */
    private fun launchUninstallDialog() {
        val launcher = activityLauncher
        if (launcher == null) {
            Log.w(TAG, "No ActivityLauncher — user must uninstall from Settings")
            return
        }
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        val launched = launcher.launch(intent)
        if (launched) {
            Log.i(TAG, "System uninstall dialog launched")
        } else {
            Log.w(TAG, "System uninstall dialog could not be launched")
        }
    }
}
