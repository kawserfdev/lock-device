package com.example.app_lock.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.UserManager
import android.util.Log
import com.example.app_lock.permissions.ActivityLauncher

/**
 * Controls the uninstall-block restriction for the App Lock demo (P-15).
 */
class UninstallBlockController(
    private val context: Context,
    private val eventPublisher: EventPublisher,
    @Volatile private var activityLauncher: ActivityLauncher? = null,
) : UninstallBlockOperations {

    override fun setActivityLauncher(launcher: ActivityLauncher?) {
        this.activityLauncher = launcher
    }

    private var onUninstallBlockChangedListener: OnUninstallBlockChangedListener? = null

    override fun setOnUninstallBlockChangedListener(listener: OnUninstallBlockChangedListener?) {
        this.onUninstallBlockChangedListener = listener
    }

    companion object {
        private const val TAG = "UninstallBlockCtrl"

        private const val DISALLOW_CONFIG_APPS = "no_config_apps"

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

    override fun isSupported(): Boolean {
        val deviceOwner = devicePolicyManager.isDeviceOwnerApp(context.packageName)
        val profileOwner = devicePolicyManager.isProfileOwnerApp(context.packageName)
        val supported = deviceOwner || profileOwner
        Log.d(TAG, "isSupported() -> deviceOwner=$deviceOwner, profileOwner=$profileOwner, supported=$supported")
        return supported
    }

    override fun isBlocked(): Boolean {
        if (!isSupported()) {
            return false
        }
        val blocked = devicePolicyManager.getUserRestrictions(adminComponent)
            ?.getBoolean(UserManager.DISALLOW_UNINSTALL_APPS) == true
        Log.d(TAG, "isBlocked() -> $blocked")
        return blocked
    }

    override fun setBlocked(blocked: Boolean): Boolean {
        Log.d(TAG, "setBlocked($blocked) invoked")

        blockIntended = blocked

        if (!isSupported()) {
            Log.w(TAG, "setBlocked($blocked) -> not supported (not Device/Profile Owner)")
            emitUninstallBlockChanged(blocked)
            return false
        }

        return try {
            if (blocked) {
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

                onUninstallBlockChangedListener?.onChanged(true)

                emitUninstallBlockChanged(true)
                true
            } else {
                onUninstallBlockChangedListener?.onChanged(false)

                devicePolicyManager.clearDeviceOwnerApp(context.packageName)
                Log.i(TAG, "Device Owner CLEARED — app is now uninstallable")

                emitPermissionChanged(false)
                emitUninstallBlockChanged(false)

                launchUninstallDialog()
                true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "setBlocked($blocked) failed", t)
            emitUninstallBlockChanged(isBlocked())
            false
        }
    }

    private fun emitUninstallBlockChanged(blocked: Boolean) {
        eventPublisher.emitUninstallBlockChanged(blocked)
    }

    private fun emitPermissionChanged(granted: Boolean) {
        eventPublisher.emitPermissionChanged(granted)
    }

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
