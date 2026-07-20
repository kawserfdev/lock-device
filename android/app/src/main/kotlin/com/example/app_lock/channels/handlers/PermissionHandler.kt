package com.example.app_lock.channels.handlers

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.app_lock.admin.AppLockDeviceAdminReceiver
import com.example.app_lock.admin.EventPublisher
import com.example.app_lock.permissions.ActivityLauncher

/**
 * Handles Device Owner / Profile Owner permission method calls from Flutter (P-15).
 */
class PermissionHandler(
    private val context: Context,
    private val eventPublisher: EventPublisher,
    private val launcher: ActivityLauncher,
) {

    companion object {
        private const val TAG = "PermissionHandler"
    }

    private val devicePolicyManager: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private val adminComponent: ComponentName by lazy {
        AppLockDeviceAdminReceiver.componentName(context)
    }

    fun checkPermission(): Boolean {
        val deviceOwner = devicePolicyManager.isDeviceOwnerApp(context.packageName)
        val profileOwner = devicePolicyManager.isProfileOwnerApp(context.packageName)
        val active = deviceOwner || profileOwner
        Log.d(TAG, "checkPermission() -> deviceOwner=$deviceOwner, profileOwner=$profileOwner, active=$active")
        return active
    }

    fun isProvisioningAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.d(TAG, "isProvisioningAllowed() -> false (API ${Build.VERSION.SDK_INT} < M)")
            return false
        }
        val allowed = devicePolicyManager.isProvisioningAllowed(
            DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
        )
        Log.d(TAG, "isProvisioningAllowed() -> $allowed")
        return allowed
    }

    fun requestPermission(): Boolean {
        Log.d(TAG, "requestPermission() -> launching managed-provisioning screen")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "requestPermission() -> managed-provisioning unavailable on API ${Build.VERSION.SDK_INT} (< M)")
            return false
        }

        val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE).apply {
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, adminComponent)
        }
        val dispatched = launcher.launch(intent)
        if (!dispatched) {
            Log.w(TAG, "No activity could handle ACTION_PROVISION_MANAGED_DEVICE")
        }
        return dispatched
    }
}
