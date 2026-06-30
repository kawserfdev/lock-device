package com.example.app_lock.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.app_lock.permissions.PermissionEventBus
import com.example.app_lock.util.Constants

/**
 * DeviceAdminReceiver for the App Lock demo.
 *
 * Registered in the manifest with the `BIND_DEVICE_ADMIN` filter and bound to
 * `res/xml/device_admin.xml`, which declares the `force-lock` policy (and
 * intentionally NOT `wipe-data`, per §5.2 demo-safety policy).
 *
 * This class is intentionally a thin skeleton: the callback overrides are
 * present and logged so the lifecycle is observable, but no business logic
 * runs yet. The real fan-out (notify event channel, persist last-lock-time)
 * is added in the business-logic step.
 *
 * Why a custom receiver at all? Because `DevicePolicyManager.lockNow` requires
 * an active admin, and an active admin requires a registered receiver bound to
 * a policy XML — there is no API-only path.
 */
class AppLockDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "AppLockAdminReceiver"

        /**
         * Builds the [ComponentName] that identifies this receiver.
         *
         * Centralised here so both [DeviceAdminController] and
         * [PermissionHandler] resolve the same component without duplicating
         * the package/class string.
         */
        fun componentName(context: Context): ComponentName {
            return ComponentName(context.packageName, AppLockDeviceAdminReceiver::class.java.name)
        }
    }

    /**
     * Called when the user activates our admin via the system screen.
     *
     * This is the *first* half of the dual-confirmation pattern (§5.1); the
     * second half is the resume-time re-check on the Flutter side. We post the
     * state change to [PermissionEventBus]; [com.example.app_lock.MainActivity]
     * subscribes and forwards it onto the Flutter EventChannel.
     */
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled")
        PermissionEventBus.post(granted = true)
    }

    /**
     * Called when the user disables our admin.
     */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device admin disabled")
        PermissionEventBus.post(granted = false)
    }

    /**
     * Called when the user starts the disable flow.
     *
     * This is the **secondary warning layer** for the uninstall-block feature.
     * When the app is a Device/Profile Owner, the true
     * `DISALLOW_UNINSTALL_APPS` restriction (managed by
     * [UninstallBlockController]) prevents uninstall outright. When the app is
     * only a plain Device Admin, the system cannot block uninstall — but it
     * *does* call this callback and display the returned [CharSequence] as a
     * warning dialog before the user confirms disabling the admin.
     *
     * We therefore return a contextual warning whenever the user has expressed
     * the intent to block uninstall ([UninstallBlockController.blockIntended]),
     * so the user is reminded that disabling the admin will allow uninstall.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? {
        Log.w(TAG, "Device admin disable requested (blockIntended=${UninstallBlockController.blockIntended})")
        return if (UninstallBlockController.blockIntended) {
            "Uninstall protection is active. Disabling this admin will allow the app to be uninstalled. Are you sure you want to continue?"
        } else {
            super.onDisableRequested(context, intent)
        }
    }

}
