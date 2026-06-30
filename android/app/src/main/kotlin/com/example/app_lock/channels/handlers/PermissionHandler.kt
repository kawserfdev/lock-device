package com.example.app_lock.channels.handlers

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.app_lock.admin.AppLockDeviceAdminReceiver
import com.example.app_lock.channels.LockEventChannel
import com.example.app_lock.permissions.ActivityLauncher

/**
 * Handles Device Owner / Profile Owner permission method calls from Flutter.
 *
 * Scope: this class is a *transport adapter* — it reads the system state via
 * [DevicePolicyManager] and forwards it back as a primitive `Boolean`. It does
 * NOT decide policy, retry, or run any business logic. The actual permission
 * flow (check → request → resume re-check) is orchestrated on the Flutter side
 * per the architecture review §5.1.
 *
 * Why Device Owner instead of plain Device Admin?
 * -----------------------------------------------
 * A plain Device Admin (granted via `ACTION_ADD_DEVICE_ADMIN`) can call
 * `lockNow()` but **cannot** set user restrictions such as
 * `DISALLOW_UNINSTALL_APPS`. Only a **Device Owner** or **Profile Owner** can
 * apply that restriction, which is what makes the uninstall-block toggle truly
 * effective. This handler therefore detects owner status (not just admin
 * status) and exposes a managed-provisioning request path.
 *
 * Provisioning reality (important):
 * --------------------------------
 * Device Owner **cannot** be granted via a simple in-app toggle on a device
 * that already has user accounts. The supported paths are:
 *
 * 1. **`adb shell dpm set-device-owner`** — the development/testing path used
 *    in both debug and release builds. Requires no accounts on the device.
 *    This is the primary path for this demo and works in release mode too.
 * 2. **`ACTION_PROVISION_MANAGED_DEVICE`** — the in-app managed-provisioning
 *    intent. Only succeeds on a factory-fresh device with zero accounts.
 *    [isProvisioningAllowed] reports whether this path is available so the
 *    Flutter UI can decide whether to show the in-app button or the adb
 *    guidance.
 *
 * Critical contract (§4.3): [requestPermission] returns `true` once the
 * managed-provisioning intent has been *launched* — NOT once the app becomes
 * Device Owner. The outcome is learned later via [checkPermission] on resume,
 * or via the `permissionChanged` event pushed from
 * [com.example.app_lock.admin.AppLockDeviceAdminReceiver]. This prevents the
 * classic "request returns before user acts" bug.
 */
class PermissionHandler(
    private val context: Context,
    private val eventChannel: LockEventChannel,
    private val launcher: ActivityLauncher,
) {

    companion object {
        private const val TAG = "PermissionHandler"
    }

    /**
     * Lazily-resolved [DevicePolicyManager] system service handle.
     */
    private val devicePolicyManager: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    /**
     * The [ComponentName] identifying our [AppLockDeviceAdminReceiver].
     *
     * Used for owner checks and for the managed-provisioning intent extra.
     */
    private val adminComponent: ComponentName by lazy {
        AppLockDeviceAdminReceiver.componentName(context)
    }

    /**
     * Returns `true` when this app is the Device Owner or Profile Owner.
     *
     * This is the *check* half of the dual-confirmation pattern: it is called
     * both on demand and on Activity resume to validate the user's choice.
     *
     * Device Owner is the strongest admin role and is required for
     * `DISALLOW_UNINSTALL_APPS`. Profile Owner (a managed-profile admin) also
     * satisfies the restriction API, so both are accepted.
     */
    fun checkPermission(): Boolean {
        val deviceOwner = devicePolicyManager.isDeviceOwnerApp(context.packageName)
        val profileOwner = devicePolicyManager.isProfileOwnerApp(context.packageName)
        val active = deviceOwner || profileOwner
        Log.d(TAG, "checkPermission() -> deviceOwner=$deviceOwner, profileOwner=$profileOwner, active=$active")
        return active
    }

    /**
     * Returns `true` when in-app Device Owner provisioning is allowed on this
     * device right now.
     *
     * Managed-provisioning (`ACTION_PROVISION_MANAGED_DEVICE`) only succeeds on
     * a device with **no** user accounts. When this returns `false`, the app
     * cannot become Device Owner via an in-app intent and the user must use the
     * `adb shell dpm set-device-owner` command instead (works in both debug and
     * release builds).
     *
     * The Flutter UI uses this to decide which guidance to show.
     */
    fun isProvisioningAllowed(): Boolean {
        // ACTION_PROVISION_MANAGED_DEVICE requires API 23+ (Marshmallow).
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

    /**
     * Launches the system managed-provisioning flow to make this app the
     * Device Owner.
     *
     * Returns `true` to signal that the request was *dispatched* (the system
     * intent was fired). It does NOT mean the app became Device Owner — the
     * Flutter side must call [checkPermission] again on resume, or listen for
     * the `permissionChanged` event, to learn the outcome.
     *
     * Implementation: builds an `ACTION_PROVISION_MANAGED_DEVICE` intent
     * carrying our [adminComponent] and delegates the actual launch to the
     * injected [ActivityLauncher] (the host Activity).
     *
     * Note: this intent only succeeds on a factory-fresh device with zero
     * accounts. On a device that already has accounts, [isProvisioningAllowed]
     * returns `false` and the user must use `adb shell dpm set-device-owner`
     * instead. The Flutter UI is responsible for checking
     * [isProvisioningAllowed] first and showing the appropriate guidance.
     */
    fun requestPermission(): Boolean {
        Log.d(TAG, "requestPermission() -> launching managed-provisioning screen")

        // ACTION_PROVISION_MANAGED_DEVICE requires API 23+ (Marshmallow).
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
        // Note: we deliberately do NOT emit a permissionChanged event here.
        // The outcome is unknown until the user acts; the receiver's
        // onEnabled/onDisabled callbacks (and the Flutter resume re-check)
        // are the source of truth.
        return dispatched
    }
}
