package com.example.app_lock.channels.handlers

import android.app.Activity
import android.util.Log
import com.example.app_lock.compliance.LockTaskController

/**
 * Handles kiosk-mode (Lock Task Mode / screen pinning) requests coming from
 * Flutter for the Status ("App Lock Time counter") screen.
 *
 * ## Purpose
 *
 * While the Status screen is visible the user must not be able to leave the
 * app via the system navigation buttons. Android's Lock Task Mode is the
 * official, supported way to disable the Home and Recents buttons (and to
 * hide the status bar / notification shade). The Back gesture is intercepted
 * on the Flutter side via `PopScope`; this handler covers the remaining
 * hardware / system-bar affordances.
 *
 * ## How it works
 *
 * 1. [enableKioskMode] arms the [LockTaskController] allowlist with the strict
 *    kiosk feature set (`LOCK_TASK_FEATURE_NONE` — every escape affordance
 *    disabled) and then calls `Activity.startLockTask()` on the host
 *    [MainActivity]. Because the app is allowlisted, the pin happens silently
 *    (no user-confirm dialog).
 * 2. [disableKioskMode] calls `Activity.stopLockTask()` and disarms the
 *    allowlist so the device returns to normal usage.
 *
 * ## Activity binding
 *
 * `startLockTask()` / `stopLockTask()` are `Activity` instance methods, so the
 * handler holds a weak reference to the host [Activity]. [MainActivity] calls
 * [attachActivity] in `configureFlutterEngine` and [detachActivity] in
 * `onDestroy`.
 *
 * ## Capability
 *
 * Lock Task Mode requires the Device Owner role (see
 * [LockTaskController.isSupported]). On a non-Device-Owner device the pin is
 * skipped and the methods return `false`; the Flutter side still intercepts
 * the Back gesture, so the screen remains non-dismissible via Back even
 * without the native pin.
 */
class KioskModeHandler {

    companion object {
        private const val TAG = "KioskModeHandler"
    }

    @Volatile
    private var hostActivity: Activity? = null

    private val lockTaskController: LockTaskController? by lazy {
        hostActivity?.let { LockTaskController(it) }
    }

    /**
     * Binds the host [Activity] so [enableKioskMode] / [disableKioskMode] can
     * call `startLockTask()` / `stopLockTask()`.
     *
     * Called by [com.example.app_lock.MainActivity.configureFlutterEngine].
     */
    fun attachActivity(activity: Activity) {
        hostActivity = activity
        Log.d(TAG, "attachActivity() — host activity bound")
    }

    /**
     * Clears the host [Activity] reference.
     *
     * Called by [com.example.app_lock.MainActivity.onDestroy] to avoid leaking
     * the activity.
     */
    fun detachActivity() {
        hostActivity = null
        Log.d(TAG, "detachActivity() — host activity cleared")
    }

    /**
     * Pins the host Activity via Lock Task Mode using the strict kiosk feature
     * set (all escape affordances disabled).
     *
     * @return `true` when the pin was started; `false` when not supported, the
     *   host activity is not bound, or `startLockTask()` threw.
     */
    fun enableKioskMode(): Boolean {
        val activity = hostActivity
        if (activity == null) {
            Log.w(TAG, "enableKioskMode() — no host activity bound")
            return false
        }
        val controller = LockTaskController(activity)
        val armed = controller.arm(LockTaskController.KIOSK_FEATURES)
        if (!armed || !controller.isAllowlisted()) {
            Log.w(TAG, "enableKioskMode() — allowlist not set (non-DO or arm failed); skipping pin")
            return false
        }
        return try {
            activity.startLockTask()
            Log.i(TAG, "enableKioskMode() — Lock Task Mode started (kiosk features)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "enableKioskMode() — startLockTask failed", t)
            false
        }
    }

    /**
     * Releases the Lock Task Mode pin applied by [enableKioskMode] and disarms
     * the allowlist.
     *
     * @return `true` when the pin was stopped (or there was nothing to stop);
     *   `false` only when an unexpected (non-"not pinned") error occurred.
     */
    fun disableKioskMode(): Boolean {
        val activity = hostActivity
        if (activity == null) {
            Log.w(TAG, "disableKioskMode() — no host activity bound; disarming allowlist only")
            return false
        }
        var stopped = true
        try {
            activity.stopLockTask()
            Log.i(TAG, "disableKioskMode() — Lock Task Mode stopped")
        } catch (e: IllegalStateException) {
            // Expected when not currently pinned — not an error.
            Log.d(TAG, "disableKioskMode() — not in Lock Task Mode (no-op)")
        } catch (t: Throwable) {
            Log.w(TAG, "disableKioskMode() — stopLockTask failed", t)
            stopped = false
        }
        runCatching { LockTaskController(activity).disarm() }
            .onFailure { Log.w(TAG, "disableKioskMode() — disarm failed", it) }
        return stopped
    }
}
