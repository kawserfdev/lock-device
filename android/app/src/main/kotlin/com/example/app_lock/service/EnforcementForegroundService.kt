package com.example.app_lock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.app_lock.R
import com.example.app_lock.admin.DeviceAdminOperations
import com.example.app_lock.admin.LocationEnforcementOperations
import com.example.app_lock.compliance.LocationComplianceCoordinator
import com.example.app_lock.util.Constants

/**
 * Unified foreground service that runs both the client-facing App Lock countdown/timed-lock
 * and the device-compliance Location Enforcement monitoring (P-08).
 *
 * This ensures that a single notification and service handle both background activities.
 * Retrieves collaborating controllers natively from [EngineBootstrap] (P-15).
 */
class EnforcementForegroundService : Service() {

    companion object {
        private const val TAG = "EnforcementFgService"

        const val ACTION_START_LOCK_MONITOR = "com.example.app_lock.action.START_LOCK_MONITOR"
        const val ACTION_START_COMPLIANCE_MONITOR = "com.example.app_lock.action.START_COMPLIANCE_MONITOR"
        const val ACTION_START_TIMED_LOCK = "com.example.app_lock.action.START_TIMED_LOCK"
        const val ACTION_STOP = "com.example.app_lock.action.STOP_SERVICE"

        const val EXTRA_DURATION_MS = "durationMs"
        const val EXTRA_STOP_LOCK_MONITOR = "stopLockMonitor"
        const val EXTRA_STOP_COMPLIANCE = "stopCompliance"

        const val CHANNEL_ID = "app_lock_enforcement_channel"
        const val NOTIFICATION_ID = 1001

        @Volatile
        @JvmStatic
        var isLockMonitorRunning: Boolean = false
            private set

        @Volatile
        @JvmStatic
        var isComplianceRunning: Boolean = false
            private set

        val isRunning: Boolean
            @JvmStatic
            get() = isLockMonitorRunning || isComplianceRunning
    }

    private val engineBootstrap: EngineBootstrap by lazy {
        EngineBootstrap.getInstance(this)
    }

    private val lockEngine: DeviceAdminOperations by lazy {
        engineBootstrap.deviceAdminOperations
    }

    private val lockoutManager: LockoutManager by lazy {
        engineBootstrap.lockoutManager
    }

    private val locationEnforcement: LocationEnforcementOperations by lazy {
        engineBootstrap.locationEnforcementOperations
    }

    private var countdownTimer: LockCountdownTimer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var selfStopRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ServiceGuard Pattern: Immediately start foreground
        updateNotification()

        val prefs = getSharedPreferences("app_lock_service_prefs", Context.MODE_PRIVATE)

        if (intent == null) {
            val wasLockRunning = prefs.getBoolean("is_lock_running", false)
            val wasComplianceRunning = prefs.getBoolean("is_compliance_running", false)
            Log.d(TAG, "Recreated via START_STICKY. wasLockRunning=$wasLockRunning, wasComplianceRunning=$wasComplianceRunning")
            
            if (wasLockRunning) {
                val action = prefs.getString("saved_action", null)
                val timedDuration = prefs.getLong("saved_duration", 0L)
                if (action == ACTION_START_TIMED_LOCK && timedDuration > 0L) {
                    val startTime = prefs.getLong("saved_start_time", 0L)
                    val elapsed = System.currentTimeMillis() - startTime
                    val remaining = timedDuration - elapsed
                    if (remaining > 0L) {
                        isLockMonitorRunning = true
                        startTimedLock(remaining)
                    } else {
                        isLockMonitorRunning = false
                        emitServiceState(Constants.SERVICE_STATUS_STOPPED)
                    }
                } else {
                    isLockMonitorRunning = true
                    startCountdown()
                }
            }

            if (wasComplianceRunning) {
                isComplianceRunning = true
                LocationComplianceCoordinator.restore(this)
                locationEnforcement.startRealtimeWatch()
            }

            updateNotification()
            checkStopSelf()
            return START_STICKY
        }

        when (intent.action) {
            ACTION_START_LOCK_MONITOR -> {
                Log.d(TAG, "ACTION_START_LOCK_MONITOR received")
                isLockMonitorRunning = true
                prefs.edit().apply {
                    putBoolean("is_lock_running", true)
                    putString("saved_action", ACTION_START_LOCK_MONITOR)
                    putLong("saved_duration", 0L)
                    putLong("saved_start_time", 0L)
                }.apply()
                startCountdown()
                updateNotification()
            }
            ACTION_START_TIMED_LOCK -> {
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, Constants.LOCKOUT_DURATION_MS)
                Log.d(TAG, "ACTION_START_TIMED_LOCK received (duration=${durationMs}ms)")
                isLockMonitorRunning = true
                prefs.edit().apply {
                    putBoolean("is_lock_running", true)
                    putString("saved_action", ACTION_START_TIMED_LOCK)
                    putLong("saved_duration", durationMs)
                    putLong("saved_start_time", System.currentTimeMillis())
                }.apply()
                startTimedLock(durationMs)
                updateNotification()
            }
            ACTION_START_COMPLIANCE_MONITOR -> {
                Log.d(TAG, "ACTION_START_COMPLIANCE_MONITOR received")
                isComplianceRunning = true
                prefs.edit().putBoolean("is_compliance_running", true).apply()
                LocationComplianceCoordinator.restore(this)
                locationEnforcement.startRealtimeWatch()
                updateNotification()
            }
            ACTION_STOP -> {
                val stopLock = intent.getBooleanExtra(EXTRA_STOP_LOCK_MONITOR, false)
                val stopCompliance = intent.getBooleanExtra(EXTRA_STOP_COMPLIANCE, false)
                val shouldStopLock = stopLock || (!stopLock && !stopCompliance)
                val shouldStopCompliance = stopCompliance || (!stopLock && !stopCompliance)

                Log.d(TAG, "ACTION_STOP received: shouldStopLock=$shouldStopLock, shouldStopCompliance=$shouldStopCompliance")

                if (shouldStopLock) {
                    isLockMonitorRunning = false
                    prefs.edit().apply {
                        putBoolean("is_lock_running", false)
                        putString("saved_action", null)
                        putLong("saved_duration", 0L)
                        putLong("saved_start_time", 0L)
                    }.apply()
                    countdownTimer?.cancel()
                    countdownTimer = null
                    selfStopRunnable?.let { mainHandler.removeCallbacks(it) }
                    selfStopRunnable = null
                    lockoutManager.clearLockout()
                    emitServiceState(Constants.SERVICE_STATUS_STOPPED)
                }

                if (shouldStopCompliance) {
                    isComplianceRunning = false
                    prefs.edit().putBoolean("is_compliance_running", false).apply()
                    locationEnforcement.stopRealtimeWatch()
                }

                updateNotification()
                checkStopSelf()
            }
        }

        return START_STICKY
    }

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

    private fun startTimedLock(durationMs: Long) {
        countdownTimer?.cancel()
        countdownTimer = null
        selfStopRunnable?.let { mainHandler.removeCallbacks(it) }
        selfStopRunnable = null

        lockoutManager.forceStartLockout(durationMs)
        lockEngine.lockNow()
        scheduleSelfStop(durationMs)

        emitServiceState(Constants.SERVICE_STATUS_RUNNING)
    }

    private fun scheduleSelfStop(durationMs: Long) {
        val runnable = Runnable {
            Log.i(TAG, "Timed-lock duration elapsed — clearing lockout and stopping lock monitor")
            lockoutManager.clearLockout()
            isLockMonitorRunning = false
            getSharedPreferences("app_lock_service_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("is_lock_running", false).apply()
            emitServiceState(Constants.SERVICE_STATUS_STOPPED)
            updateNotification()
            checkStopSelf()
        }
        selfStopRunnable = runnable
        mainHandler.postDelayed(runnable, durationMs)
    }

    private fun checkStopSelf() {
        if (!isRunning) {
            Log.d(TAG, "No monitors running — stopping service completely")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateNotification() {
        createNotificationChannel()

        val title: String
        val text: String
        if (isLockMonitorRunning && isComplianceRunning) {
            title = getString(R.string.foreground_notification_title) + " & " + getString(R.string.location_compliance_notification_title)
            text = getString(R.string.foreground_notification_text)
        } else if (isComplianceRunning) {
            title = getString(R.string.location_compliance_notification_title)
            text = getString(R.string.location_compliance_notification_text)
        } else {
            title = getString(R.string.foreground_notification_title)
            text = getString(R.string.foreground_notification_text)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Lock Enforcement Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun emitServiceState(status: String) {
        engineBootstrap.eventBridge.emitServiceStateChanged(status)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy()")
        countdownTimer?.cancel()
        countdownTimer = null
        selfStopRunnable?.let { mainHandler.removeCallbacks(it) }
        selfStopRunnable = null
        isLockMonitorRunning = false
        isComplianceRunning = false
    }
}
