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
import com.example.app_lock.admin.DeviceAdminOperations
import com.example.app_lock.admin.EventPublisher
import com.example.app_lock.util.Constants

/**
 * Enforces a time-bounded "lockout window" after a successful device lock (P-15).
 * Resets the lockout receiver dynamically and registers it with standard flags.
 */
class LockoutManager(
    private val context: Context,
    private val eventPublisher: EventPublisher
) {

    companion object {
        private const val TAG = "LockoutManager"
    }

    private val appContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private val lockEngine: DeviceAdminOperations by lazy {
        EngineBootstrap.getInstance(appContext).deviceAdminOperations
    }

    @Volatile
    private var lockoutStartUptimeMs: Long = 0L

    @Volatile
    private var lockoutDurationMs: Long = Constants.LOCKOUT_DURATION_MS

    @Volatile
    private var receiverRegistered = false

    private val lockoutEndRunnable = Runnable { clearLockout() }

    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                onUserPresent()
            }
        }
    }

    fun isLockoutActive(): Boolean {
        if (lockoutStartUptimeMs == 0L) return false
        val elapsed = SystemClock.elapsedRealtime() - lockoutStartUptimeMs
        return elapsed < lockoutDurationMs
    }

    fun startLockout(durationMs: Long = Constants.LOCKOUT_DURATION_MS) {
        if (isLockoutActive()) {
            Log.d(TAG, "startLockout() — window already active, ignoring")
            return
        }
        armLockout(durationMs)
    }

    fun forceStartLockout(durationMs: Long) {
        if (isLockoutActive()) {
            Log.d(TAG, "forceStartLockout() — replacing active window")
        }
        armLockout(durationMs)
    }

    private fun armLockout(durationMs: Long) {
        handler.removeCallbacks(lockoutEndRunnable)

        lockoutDurationMs = if (durationMs > 0L) durationMs else Constants.LOCKOUT_DURATION_MS
        Log.i(TAG, "Lockout window armed (${lockoutDurationMs} ms)")
        lockoutStartUptimeMs = SystemClock.elapsedRealtime()

        if (!receiverRegistered) {
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            ContextCompat.registerReceiver(
                appContext,
                userPresentReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
        }

        handler.postDelayed(lockoutEndRunnable, lockoutDurationMs)
        emitLockoutState(active = true)
    }

    private fun onUserPresent() {
        if (!isLockoutActive()) return
        Log.i(TAG, "USER_PRESENT during lockout — re-locking immediately")
        lockEngine.lockNow()
    }

    fun clearLockout() {
        if (lockoutStartUptimeMs == 0L) {
            Log.d(TAG, "clearLockout() — no active window, ignoring")
            return
        }
        Log.i(TAG, "Lockout window elapsed — clearing")
        lockoutStartUptimeMs = 0L
        handler.removeCallbacks(lockoutEndRunnable)
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(userPresentReceiver) }
            receiverRegistered = false
        }
        emitLockoutState(active = false)
    }

    private fun emitLockoutState(active: Boolean) {
        eventPublisher.emitLockoutStateChanged(active)
    }
}
