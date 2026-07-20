package com.example.app_lock.service

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.app_lock.util.Constants

/**
 * One-shot countdown timer that fires [onComplete] after [Constants.LOCK_DELAY_MS].
 *
 * Per architecture review §6.1 ("Start → wait predefined time → trigger lock")
 * and §10 ("Sleeping coroutine (suspended) — near-zero CPU"), the wait is a
 * single delayed post rather than a polling loop. This keeps CPU usage at
 * effectively zero while the timer is armed.
 *
 * Implementation note: a plain [Handler.postDelayed] is used instead of
 * `CountDownTimer` because we do not need per-tick updates — only the final
 * fire. The handler is bound to the main looper so the callback runs on the
 * platform thread, where it is safe to call `DevicePolicyManager.lockNow()`.
 *
 * The timer is cancellable: [cancel] removes the pending callback so that
 * stopping the service (or an OS kill) does not leave a dangling lock trigger.
 */
class LockCountdownTimer(
    private val onComplete: () -> Unit,
) {

    companion object {
        private const val TAG = "LockCountdownTimer"
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Whether the timer is currently armed (waiting to fire). */
    @Volatile
    private var armed: Boolean = false

    private var pendingRunnable: Runnable? = null

    /**
     * Arms the timer: schedules [onComplete] to run after
     * [Constants.LOCK_DELAY_MS].
     *
     * If already armed, the previous callback is cancelled first so only one
     * fire is ever pending.
     */
    fun start() {
        if (armed) {
            Log.w(TAG, "start() called while already armed — re-arming")
            cancel()
        }
        Log.d(TAG, "Arming countdown for ${Constants.LOCK_DELAY_MS}ms")
        armed = true
        val runnable = Runnable {
            armed = false
            pendingRunnable = null
            Log.d(TAG, "Countdown elapsed — firing onComplete")
            onComplete()
        }
        pendingRunnable = runnable
        handler.postDelayed(runnable, Constants.LOCK_DELAY_MS)
    }

    /**
     * Cancels the pending callback if armed.
     *
     * Safe to call when not armed (no-op).
     */
    fun cancel() {
        if (armed || pendingRunnable != null) {
            Log.d(TAG, "Cancelling armed countdown")
            pendingRunnable?.let { handler.removeCallbacks(it) }
            pendingRunnable = null
            armed = false
        }
    }

    /** Whether the timer is currently armed. */
    fun isArmed(): Boolean = armed
}
