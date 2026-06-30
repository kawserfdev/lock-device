package com.example.app_lock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.app_lock.R
import com.example.app_lock.admin.DeviceAdminController
import com.example.app_lock.channels.LockEventChannel
import com.example.app_lock.util.Constants

/**
 * Foreground service that runs the lock countdown timer and triggers
 * [DeviceAdminController.lockNow] when the static OVERDUE condition is met
 * (architecture review §6).
 *
 * Behaviour model (§6.1):
 * 1. `startForeground` is called *immediately* in `onStartCommand` (Android 14+
 *    requires this within 5s of `startForegroundService`).
 * 2. A [LockCountdownTimer] is armed for [Constants.LOCK_DELAY_MS].
 * 3. When the timer fires, if [Constants.OVERDUE] is `true`, the lock engine is
 *    invoked. The lock engine validates admin-active + not-already-locked, then
 *    calls `DevicePolicyManager.lockNow()`.
 * 4. A `serviceStateChanged(running)` event is emitted when the timer starts;
 *    a `serviceStateChanged(stopped)` event is emitted when the service stops.
 *
 * Restart policy (§6.3): returns [START_STICKY] so the OS recreates the service
 * with a null intent after a kill — the timer is re-armed fresh (no persisted
 * elapsed time, per invariant §9.2).
 *
 * Event emission: the service reaches Flutter through the
 * [LockEventChannel.instance] static holder (set by `MainActivity`). When
 * Flutter is not running, emissions are no-ops — the lock still executes.
 */
class AppLockForegroundService : Service() {

    companion object {
        private const val TAG = "AppLockFgService"

        /** Intent action that asks the service to start (enter foreground). */
        const val ACTION_START = "com.example.app_lock.action.START_SERVICE"

        /** Intent action that asks the service to stop. */
        const val ACTION_STOP = "com.example.app_lock.action.STOP_SERVICE"

        /**
         * In-process flag mirroring whether the service is running.
         *
         * Accurate for the lifetime of the current process. The service is the
         * single owner of this flag (set in `startForegroundService`, cleared in
         * `stopForegroundService` / `onDestroy`).
         */
        @Volatile
        @JvmStatic
        var isRunning: Boolean = false
            private set
    }

    /**
     * The lock engine. Constructed lazily with `eventChannel = null` so it
     * emits through the [LockEventChannel.instance] static holder — the service
     * is system-instantiated and cannot receive constructor injection.
     */
    private val lockEngine: DeviceAdminController by lazy {
        DeviceAdminController(context = this, eventChannel = null)
    }

    /** The countdown timer; armed on start, cancelled on stop/destroy. */
    private var countdownTimer: LockCountdownTimer? = null

    /**
     * No binding is supported — this is a started service only.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Handles start/stop intents.
     *
     * `startForeground` is called *before* any other work so we never violate
     * the Android 14 5-second deadline.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP received")
                stopForegroundService()
                return START_NOT_STICKY
            }
            else -> {
                // ACTION_START or a null-intent START_STICKY restart (§6.3).
                Log.d(TAG, "Entering foreground (action=${intent?.action})")
                startForegroundService()
                startCountdown()
            }
        }

        // START_STICKY so the OS recreates us after a kill (§6.3).
        return START_STICKY
    }

    /**
     * Arms the lock countdown timer.
     *
     * When the timer fires, the static [Constants.OVERDUE] flag is consulted.
     * If `true`, the lock engine is invoked — this is the static lock logic
     * described in §7.1. The lock engine performs its own validation
     * (admin-active + not-already-locked) and emits a `lockResult` event.
     *
     * A `serviceStateChanged(running)` event is emitted so Flutter learns the
     * service has entered its active phase.
     */
    private fun startCountdown() {
        countdownTimer?.cancel()
        countdownTimer = LockCountdownTimer {
            Log.d(TAG, "Countdown elapsed — evaluating OVERDUE flag")
            if (Constants.OVERDUE) {
                Log.i(TAG, "OVERDUE=true — triggering native lock")
                lockEngine.lockNow()
            } else {
                Log.d(TAG, "OVERDUE=false — no lock triggered")
            }
        }.also { it.start() }

        emitServiceState(Constants.SERVICE_STATUS_RUNNING)
    }

    /**
     * Creates the notification channel (Android 8+) and calls `startForeground`
     * with a low-importance notification (no sound, per §6.2).
     */
    private fun startForegroundService() {
        createNotificationChannel()

        val notification: Notification = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.foreground_notification_title))
            .setContentText(getString(R.string.foreground_notification_text))
            .setSmallIcon(R.drawable.ic_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires an explicit foreground service type.
            startForeground(
                Constants.FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Constants.FOREGROUND_NOTIFICATION_ID, notification)
        }

        isRunning = true
    }

    /**
     * Tears down the foreground state and stops the service.
     *
     * Cancels the countdown so a stop does not leave a dangling lock trigger,
     * and emits `serviceStateChanged(stopped)` so Flutter updates its UI.
     */
    private fun stopForegroundService() {
        countdownTimer?.cancel()
        countdownTimer = null
        emitServiceState(Constants.SERVICE_STATUS_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        isRunning = false
    }

    /**
     * Creates the low-importance notification channel (idempotent).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                Constants.NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW, // no sound (§6.2)
            ).apply {
                description = getString(R.string.foreground_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Emits a `serviceStateChanged` event through the static channel holder.
     *
     * No-op when Flutter is not listening (invariant §9.2).
     */
    private fun emitServiceState(status: String) {
        LockEventChannel.instance?.emitServiceStateChanged(status)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy()")
        countdownTimer?.cancel()
        countdownTimer = null
        isRunning = false
    }
}
