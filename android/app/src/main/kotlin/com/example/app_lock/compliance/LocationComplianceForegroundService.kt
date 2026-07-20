package com.example.app_lock.compliance

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
import com.example.app_lock.admin.LocationEnforcementController
import com.example.app_lock.admin.LocationProviderChangeReceiver
import com.example.app_lock.util.Constants

/**
 * Foreground service that keeps the app process alive so the Location
 * Compliance Enforcement System survives screen-off, app-swipe, and low-memory
 * trimming.
 *
 * ## Why a foreground service
 *
 * The real-time layer of the enforcement system is a runtime-registered
 * [LocationProviderChangeReceiver] for `android.location.PROVIDERS_CHANGED`.
 * That broadcast cannot be registered in the manifest on Android 8.0+ (it is
 * not on the implicit-broadcast exception list), so the receiver only lives
 * while the app process is alive. When the OS kills the process (low memory,
 * the user swiping the app away), the receiver is lost — leaving a gap until
 * the next WorkManager tick (~15 minutes).
 *
 * A foreground service makes the process high-priority: the OS will not kill
 * it under normal memory pressure, so the runtime receiver and the
 * [LocationComplianceCoordinator] countdowns keep running across screen-off
 * and app-swipe. The WorkManager worker remains as the self-healing fallback
 * for the rare case where the process is nonetheless killed.
 *
 * ## Lifecycle
 *
 * - **Started** by [LocationComplianceCoordinator.start] when the "Uninstall
 *   Block" is turned ON.
 * - **Stopped** by [LocationComplianceCoordinator.stop] when the block is
 *   turned OFF.
 * - **Restarted** by the OS with a `null` intent after a kill
 *   ([START_STICKY]); `onStartCommand` then calls
 *   [LocationComplianceCoordinator.restore] to rebuild the state machine from
 *   the persisted store and re-register the runtime receiver.
 *
 * ## Notification
 *
 * A low-importance, ongoing notification (no sound) is shown for the lifetime
 * of the service, as required for a foreground service. A dedicated
 * notification channel ([CHANNEL_ID]) keeps the compliance service's
 * notification separate from the lock service's.
 *
 * ## Android 14+ (API 34+)
 *
 * Declared in the manifest with `foregroundServiceType="specialUse"` and a
 * `<property>` describing the use case, matching the pattern used by
 * [com.example.app_lock.service.AppLockForegroundService]. `startForeground`
 * is called with [ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE] on API 34+.
 */
class LocationComplianceForegroundService : Service() {

    companion object {
        private const val TAG = "LocComplianceFgSvc"

        /** Dedicated notification channel id for the compliance service. */
        const val CHANNEL_ID = "app_lock_compliance_channel"

        /** Notification id (must be > 0 and distinct from the lock service's). */
        const val NOTIFICATION_ID = 1002

        /**
         * In-process flag mirroring whether the compliance service is running.
         * Accurate for the lifetime of the current process.
         */
        @Volatile
        @JvmStatic
        var isRunning: Boolean = false
            private set
    }

    /**
     * No binding is supported — this is a started service only.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Enters foreground immediately (Android 14+ requires this within 5s of
     * `startForegroundService`), then restores the coordinator state.
     *
     * A `null` intent (START_STICKY restart after a kill) is handled the same
     * as a real start: [LocationComplianceCoordinator.restore] reads the
     * persisted store and resumes the appropriate enforcement step, and the
     * runtime [LocationProviderChangeReceiver] is re-registered so the
     * real-time layer self-heals.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand() — action=${intent?.action}")
        startForegroundService()
        // Restore the coordinator state from the persisted store. This
        // re-arms the runtime receiver and resumes any in-progress countdown
        // / compliance screen / lock loop that was lost when the process died.
        LocationComplianceCoordinator.restore(this)
        // Re-register the real-time PROVIDERS_CHANGED receiver so the
        // real-time layer self-heals after process death.
        LocationEnforcementController(this).startRealtimeWatch()
        // START_STICKY so the OS recreates us after a kill.
        return START_STICKY
    }

    /**
     * Creates the notification channel (Android 8+) and calls `startForeground`
     * with a low-importance notification (no sound).
     */
    private fun startForegroundService() {
        createNotificationChannel()

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.location_compliance_notification_title))
            .setContentText(getString(R.string.location_compliance_notification_text))
            .setSmallIcon(R.drawable.ic_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires an explicit foreground service type.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRunning = true
    }

    /**
     * Creates the low-importance notification channel (idempotent).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.location_compliance_channel_name),
                NotificationManager.IMPORTANCE_LOW, // no sound
            ).apply {
                description = getString(R.string.location_compliance_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy()")
        isRunning = false
    }
}
