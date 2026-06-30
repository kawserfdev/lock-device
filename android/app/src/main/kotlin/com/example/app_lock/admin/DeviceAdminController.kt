package com.example.app_lock.admin

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.example.app_lock.channels.LockEventChannel
import com.example.app_lock.service.LockoutManager
import com.example.app_lock.util.Constants

/**
 * Native Lock Engine — the single component that actually triggers a device lock.
 *
 * Responsibilities (per architecture review §5.3):
 * 1. **Validation** — admin must be active and the device must not already be
 *    locked (unless a `force` flag is supplied).
 * 2. **Execution** — call [DevicePolicyManager.lockNow].
 * 3. **Outcome reporting** — emit a typed `lockResult` event so Flutter learns
 *    success vs. already-locked vs. permission-missing vs. failed.
 *
 * Two construction paths exist:
 * - **MainActivity** constructs an instance with a concrete [eventChannel]
 *   (the live sink). This instance serves the manual `lockNow` MethodChannel
 *   call.
 * - **AppLockForegroundService** constructs an instance with `eventChannel =
 *   null` and relies on the [LockEventChannel.instance] static holder. This is
 *   necessary because the service is system-instantiated and cannot receive
 *   constructor injection. When the static holder is set (Flutter running),
 *   events reach Flutter; when it is `null` (Flutter absent), emission is a
 *   no-op and the lock still executes — matching invariant §9.2.
 */
class DeviceAdminController(
    private val context: Context,
    private val eventChannel: LockEventChannel? = null,
) {

    companion object {
        private const val TAG = "DeviceAdminController"
    }

    private val devicePolicyManager: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private val keyguardManager: KeyguardManager by lazy {
        context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    }

    private val adminComponent: ComponentName by lazy {
        AppLockDeviceAdminReceiver.componentName(context)
    }

    /**
     * Attempts to lock the device immediately.
     *
     * Flow:
     * - If neither admin nor owner is active → emit `permission_missing`,
     *   return `false`.
     * - If the device is already locked → emit `already_locked`, return `false`.
     * - Otherwise → call `lockNow()` and emit `success`, return `true`.
     * - On any unexpected failure → emit `failed`, return `false`.
     *
     * Permission model: `lockNow()` is available to both a plain Device Admin
     * and a Device/Profile Owner. We therefore accept either role. The
     * uninstall-block restriction, by contrast, requires an owner role and is
     * gated separately in [UninstallBlockController].
     *
     * @return `true` only when the lock was actually executed.
     */
    fun lockNow(): Boolean {
        Log.d(TAG, "lockNow() invoked")

        // 1. Validate permission (defense-in-depth — also checked in Flutter).
        //    Accept either an active admin (ACTION_ADD_DEVICE_ADMIN path) or a
        //    Device/Profile Owner (managed-provisioning / adb path). Both can
        //    call lockNow().
        val adminActive = devicePolicyManager.isAdminActive(adminComponent)
        val deviceOwner = devicePolicyManager.isDeviceOwnerApp(context.packageName)
        val profileOwner = devicePolicyManager.isProfileOwnerApp(context.packageName)
        if (!adminActive && !deviceOwner && !profileOwner) {
            Log.w(TAG, "No admin/owner role active — refusing to lock")
            emitLockResult(Constants.LOCK_RESULT_PERMISSION_MISSING)
            return false
        }

        // 2. Validate not already locked (best-effort; KeyguardManager check).
        //    Avoids a redundant lockNow() call when the screen is already off.
        if (keyguardManager.isKeyguardLocked) {
            Log.d(TAG, "Device already locked — short-circuiting")
            emitLockResult(Constants.LOCK_RESULT_ALREADY_LOCKED)
            return false
        }

        // 3. Execute the lock.
        return try {
            devicePolicyManager.lockNow()
            Log.i(TAG, "lockNow() -> device locked")
            emitLockResult(Constants.LOCK_RESULT_SUCCESS)
            // 4. Open the post-lock lockout window so unlock attempts are
            //    re-locked for LOCKOUT_DURATION_MS. Safe to call from any
            //    construction path — LockoutManager owns its own lock engine.
            LockoutManager.startLockout()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "lockNow() failed", t)
            emitLockResult(Constants.LOCK_RESULT_FAILED)
            false
        }
    }

    /**
     * Emits a `lockResult` event through whichever channel is available.
     *
     * Prefers the injected [eventChannel]; falls back to the static
     * [LockEventChannel.instance] holder (used by the service-created
     * instance). When neither is set, the call is a no-op.
     */
    private fun emitLockResult(result: String) {
        val channel = eventChannel ?: LockEventChannel.instance
        channel?.emitLockResult(result)
    }
}
