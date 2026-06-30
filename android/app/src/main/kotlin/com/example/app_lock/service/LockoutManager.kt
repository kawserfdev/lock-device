package com.example.app_lock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.app_lock.admin.DeviceAdminController
import com.example.app_lock.channels.LockEventChannel
import com.example.app_lock.util.Constants

/**
 * Enforces a time-bounded "lockout window" after a successful device lock.
 *
 * Requirement: once the device is locked (by the manual lock command or by the
 * background countdown timer), the user must not be able to unlock for a
 * configurable duration (default [Constants.LOCKOUT_DURATION_MS] = 1 minute,
 * overridable via [startLockout]'s `durationMs` parameter). Any unlock attempt
 * during that window is countered by an immediate re-lock.
 *
 * Mechanism:
 * - [startLockout] records the start time, registers a [BroadcastReceiver]
 *   for [Intent.ACTION_USER_PRESENT] (the system broadcast fired when the user
 *   dismisses the keyguard), and arms a [Handler] callback that clears the
 *   lockout after the duration.
 * - On USER_PRESENT during the active window, the receiver calls
 *   [DeviceAdminController.lockNow] to re-lock immediately.
 * - When the duration elapses, [clearLockout] unregisters the receiver and
 *   emits an `inactive` event so Flutter knows the device may be unlocked.
 *
 * Design notes:
 * - **Singleton** (`object`) — the lockout state is process-global; both the
 *   MainActivity-created lock engine and the service-created lock engine call
 *   [startLockout] on a successful lock.
 * - **Idempotent start** — calling [startLockout] while a window is already
 *   active is a no-op; the timer is NOT reset. This ensures a fixed window
 *   (whose length is set by the initial call) from the *initial* lock, not
 *   from the most recent re-lock.
 * - **Static holder emission** — events are pushed through
 *   [LockEventChannel.instance]; when Flutter is absent the emission is a
 *   no-op but the re-lock still executes (invariant §9.2).
 * - **Monotonic clock** — uses [SystemClock.elapsedRealtime] so wall-clock
 *   changes do not affect the window.
 */
object LockoutManager {

    private const val TAG = "LockoutManager"

    private val handler = Handler(Looper.getMainLooper())

    /** Application context, set once via [init]. */
    @Volatile
    private var appContext: Context? = null

    /** Owned lock engine used for re-locking on USER_PRESENT. */
    @Volatile
    private var lockEngine: DeviceAdminController? = null

    /** Elapsed-realtime marker of the lockout start, or `0L` when inactive. */
    @Volatile
    private var lockoutStartUptimeMs: Long = 0L

    /**
     * Duration (ms) of the current lockout window. Defaults to
     * [Constants.LOCKOUT_DURATION_MS] and is overridden per-call by
     * [startLockout] when a custom duration is supplied.
     */
    @Volatile
    private var lockoutDurationMs: Long = Constants.LOCKOUT_DURATION_MS

    /** Whether the USER_PRESENT receiver is currently registered. */
    @Volatile
    private var receiverRegistered = false

    /** Runnable posted to [handler] to clear the lockout when the window ends. */
    private val lockoutEndRunnable = Runnable { clearLockout() }

    /**
     * Listens for ACTION_USER_PRESENT and re-locks if the lockout is active.
     */
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                onUserPresent()
            }
        }
    }

    /**
     * Initialises the manager with an application context and a dedicated lock
     * engine. Idempotent — safe to call from both [com.example.app_lock.MainActivity]
     * and the foreground service; only the first call takes effect.
     */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        lockEngine = DeviceAdminController(
            context = appContext!!,
            eventChannel = null,
        )
        Log.d(TAG, "Initialised")
    }

    /**
     * `true` while the lockout window is open (started and not yet elapsed).
     */
    fun isLockoutActive(): Boolean {
        if (lockoutStartUptimeMs == 0L) return false
        val elapsed = SystemClock.elapsedRealtime() - lockoutStartUptimeMs
        return elapsed < lockoutDurationMs
    }

    /**
     * Opens the lockout window. Idempotent — if a window is already open this
     * is a no-op (the timer is NOT reset), so the window is measured from the
     * *initial* lock, not from subsequent re-locks.
     *
     * @param durationMs How long (ms) the device must stay locked. Any unlock
     * attempt within this window triggers an immediate re-lock. Defaults to
     * [Constants.LOCKOUT_DURATION_MS] (1 minute) for the standard lock path.
     */
    fun startLockout(durationMs: Long = Constants.LOCKOUT_DURATION_MS) {
        if (isLockoutActive()) {
            Log.d(TAG, "startLockout() — window already active, ignoring")
            return
        }
        val context = appContext ?: run {
            Log.w(TAG, "startLockout() — not initialised, ignoring")
            return
        }

        lockoutDurationMs = if (durationMs > 0L) durationMs else Constants.LOCKOUT_DURATION_MS
        Log.i(TAG, "Lockout window opened (${lockoutDurationMs} ms)")
        lockoutStartUptimeMs = SystemClock.elapsedRealtime()

        if (!receiverRegistered) {
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            ContextCompat.registerReceiver(
                context,
                userPresentReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }

        handler.postDelayed(lockoutEndRunnable, lockoutDurationMs)
        emitLockoutState(active = true)
    }

    /**
     * Called by [userPresentReceiver] when the user unlocks during the window.
     * Re-locks immediately via the owned lock engine.
     */
    private fun onUserPresent() {
        if (!isLockoutActive()) return
        Log.i(TAG, "USER_PRESENT during lockout — re-locking immediately")
        lockEngine?.lockNow()
    }

    /**
     * Clears the lockout window: resets the marker, unregisters the receiver,
     * and emits an `inactive` event.
     */
    private fun clearLockout() {
        Log.i(TAG, "Lockout window elapsed — clearing")
        lockoutStartUptimeMs = 0L
        handler.removeCallbacks(lockoutEndRunnable)
        val context = appContext
        if (receiverRegistered && context != null) {
            runCatching { context.unregisterReceiver(userPresentReceiver) }
            receiverRegistered = false
        }
        emitLockoutState(active = false)
    }

    /**
     * Pushes a `lockoutStateChanged` event through the static event-channel
     * holder. No-op when Flutter is not listening.
     */
    private fun emitLockoutState(active: Boolean) {
        LockEventChannel.instance?.emitLockoutStateChanged(active)
    }
}
