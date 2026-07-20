package com.example.app_lock.compliance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Runtime-registered [BroadcastReceiver] that detects device unlock
 * (`ACTION_USER_PRESENT`) while the compliance system is in the
 * [LocationComplianceState.LOCKED] state, so the coordinator can re-verify
 * location and resume the lock-then-verify loop.
 *
 * ## Why a runtime receiver
 *
 * After [LocationEnforcementController.lockNow] fires, the device is locked
 * and the user must authenticate to unlock. When they do, the system
 * broadcasts `ACTION_USER_PRESENT`. The coordinator listens for this to:
 *
 * - **Location ON** → end the loop (transition to
 *   [LocationComplianceState.LOCATION_RESTORED] →
 *   [LocationComplianceState.NORMAL]).
 * - **Location OFF** → restart the grace countdown (transition to
 *   [LocationComplianceState.COUNTDOWN_RUNNING]); if it expires again, the
 *   device is locked again, and the loop repeats indefinitely until location
 *   is enabled.
 *
 * `ACTION_USER_PRESENT` is a protected system broadcast. It is **not** on the
 * implicit-broadcast exception list, so it must be registered at runtime
 * (manifest registration does not work on Android 8.0+). The trade-off is that
 * the receiver only lives while the app process is alive — but the
 * [EnforcementForegroundService] keeps the process alive, and the
 * WorkManager worker re-registers it on each tick as a self-healing fallback.
 *
 * ## Android 13+ (API 33+) flag
 *
 * Registered with [ContextCompat.RECEIVER_EXPORTED] because `ACTION_USER_PRESENT`
 * is a system broadcast delivered by the OS keyguard coordinator (UID 1000). On
 * Android 13+ (API 33+), dynamically registered receivers for system broadcasts
 * MUST be marked as exported; otherwise, delivery is silently blocked by the
 * security framework.
 *
 * ## Lifecycle
 *
 * - [register] is called by [LocationComplianceCoordinator] when it escalates
 *   to [LocationComplianceState.LOCKED].
 * - [unregister] is called by [LocationComplianceCoordinator] when the loop
 *   ends (location restored) or when the user unlocks (the receiver is
 *   one-shot per lock cycle).
 *
 * Both methods are idempotent and thread-safe (guarded by `@Synchronized` on
 * the companion object, since the broadcast-delivery thread and the
 * coordinator's main-thread Handler may both call [unregister]).
 */
class LocationComplianceUnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_USER_PRESENT) {
            Log.d(TAG, "onReceive() — ignoring action=${intent?.action}")
            return
        }
        Log.i(TAG, "ACTION_USER_PRESENT — user unlocked; notifying coordinator")
        LocationComplianceCoordinator.onUserUnlocked()
    }

    companion object {
        private const val TAG = "LocComplianceUnlockRx"

        /** The single shared receiver instance. */
        private val receiver = LocationComplianceUnlockReceiver()

        /**
         * Process-global flag recording whether the receiver is currently
         * registered. Volatile because it is written on the coordinator's
         * main-thread Handler and read on the broadcast-delivery thread.
         */
        @Volatile
        private var registered = false

        /**
         * Registers the receiver for `ACTION_USER_PRESENT` if it is not
         * already registered. Idempotent and thread-safe.
         */
        @Synchronized
        fun register(context: Context?) {
            if (registered) {
                Log.d(TAG, "register() — already registered, ignoring")
                return
            }
            val appContext = context?.applicationContext ?: return
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            // ACTION_USER_PRESENT is a system broadcast (sent by the OS when the
            // user dismisses the keyguard), so the receiver MUST be registered
            // as EXPORTED to receive it. On Android 13+ (API 33+), a receiver
            // registered without an explicit flag defaults to NOT_EXPORTED,
            // which silently blocks system-originated broadcasts.
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            registered = true
            Log.i(TAG, "register() — ACTION_USER_PRESENT receiver registered")
        }

        /**
         * Unregisters the receiver if it is currently registered. Idempotent
         * and thread-safe.
         */
        @Synchronized
        fun unregister(context: Context?) {
            if (!registered) {
                Log.d(TAG, "unregister() — not registered, ignoring")
                return
            }
            val appContext = context?.applicationContext
            if (appContext != null) {
                runCatching { appContext.unregisterReceiver(receiver) }
            }
            registered = false
            Log.i(TAG, "unregister() — ACTION_USER_PRESENT receiver unregistered")
        }

        /** Returns `true` when the receiver is currently registered. */
        fun isRegistered(): Boolean = registered
    }
}
