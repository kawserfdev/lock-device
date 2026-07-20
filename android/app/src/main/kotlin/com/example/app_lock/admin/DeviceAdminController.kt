package com.example.app_lock.admin

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.example.app_lock.util.Constants
import com.example.app_lock.service.EngineBootstrap

/**
 * Native Lock Engine — the single component that actually triggers a device lock.
 *
 * Responsibilities (per architecture review §5.3):
 * 1. **Validation** — admin must be active and the device must not already be
 *    locked (unless a `force` flag is supplied).
 * 2. **Execution** — call [DevicePolicyManager.lockNow].
 * 3. **Outcome reporting** — emit a typed `lockResult` event so Flutter learns
 *    success vs. already-locked vs. permission-missing vs. failed.
 */
class DeviceAdminController(
    private val context: Context,
    private val eventPublisher: EventPublisher,
) : DeviceAdminOperations {

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
     * Attempts to lock the device immediately, opening a post-lock lockout
     * window of the default duration ([Constants.LOCKOUT_DURATION_MS]).
     *
     * @return `true` only when the lock was actually executed.
     */
    override fun lockNow(): Boolean = performLock(Constants.LOCKOUT_DURATION_MS)

    /**
     * Attempts to lock the device immediately, opening a post-lock lockout
     * window of the given [durationMs].
     *
     * @param durationMs How long (ms) the device must stay locked.
     * @return `true` only when the lock was actually executed.
     */
    override fun lockForDuration(durationMs: Long): Boolean {
        val safe = if (durationMs > 0L) durationMs else Constants.LOCKOUT_DURATION_MS
        return performLock(safe)
    }

    /**
     * Shared lock pipeline used by both [lockNow] and [lockForDuration].
     */
    private fun performLock(lockoutDurationMs: Long): Boolean {
        Log.d(TAG, "performLock() invoked, lockoutDuration=${lockoutDurationMs}ms")

        val adminActive = devicePolicyManager.isAdminActive(adminComponent)
        val deviceOwner = devicePolicyManager.isDeviceOwnerApp(context.packageName)
        val profileOwner = devicePolicyManager.isProfileOwnerApp(context.packageName)
        if (!adminActive && !deviceOwner && !profileOwner) {
            Log.w(TAG, "No admin/owner role active — refusing to lock")
            emitLockResult(Constants.LOCK_RESULT_PERMISSION_MISSING)
            return false
        }

        if (keyguardManager.isKeyguardLocked) {
            Log.d(TAG, "Device already locked — short-circuiting")
            emitLockResult(Constants.LOCK_RESULT_ALREADY_LOCKED)
            return false
        }

        return try {
            devicePolicyManager.lockNow()
            Log.i(TAG, "lockNow() -> device locked")
            emitLockResult(Constants.LOCK_RESULT_SUCCESS)
            // Open the post-lock lockout window
            EngineBootstrap.getInstance(context).lockoutManager.startLockout(lockoutDurationMs)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "lockNow() failed", t)
            emitLockResult(Constants.LOCK_RESULT_FAILED)
            false
        }
    }

    private fun emitLockResult(result: String) {
        eventPublisher.emitLockResult(result)
    }
}
