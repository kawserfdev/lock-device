package com.example.app_lock.channels.handlers

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.app_lock.admin.DeviceAdminOperations
import com.example.app_lock.admin.EventPublisher
import com.example.app_lock.service.EnforcementForegroundService
import com.example.app_lock.util.Constants

/**
 * Handles lock and service-control method calls coming from Flutter (P-15).
 */
class AppLockHandler(
    private val context: Context,
    private val eventPublisher: EventPublisher,
    private val lockEngine: DeviceAdminOperations,
) {

    companion object {
        private const val TAG = "AppLockHandler"
    }

    fun lockNow(): Boolean {
        Log.d(TAG, "lockNow() -> delegating to DeviceAdminController")
        return lockEngine.lockNow()
    }

    fun lockForDuration(durationMs: Long): Boolean {
        Log.d(TAG, "lockForDuration($durationMs ms) -> starting timed-lock service")
        return try {
            val intent = Intent(context, EnforcementForegroundService::class.java).apply {
                action = EnforcementForegroundService.ACTION_START_TIMED_LOCK
                putExtra(EnforcementForegroundService.EXTRA_DURATION_MS, durationMs)
            }
            context.startForegroundService(intent)
            eventPublisher.emitServiceStateChanged(Constants.SERVICE_STATUS_TRANSITIONING)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start timed-lock service", t)
            false
        }
    }

    fun startService(): Boolean {
        Log.d(TAG, "startService() -> launching EnforcementForegroundService")
        return try {
            val intent = Intent(context, EnforcementForegroundService::class.java).apply {
                action = EnforcementForegroundService.ACTION_START_LOCK_MONITOR
            }
            context.startForegroundService(intent)
            eventPublisher.emitServiceStateChanged(Constants.SERVICE_STATUS_TRANSITIONING)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start foreground service", t)
            false
        }
    }

    fun stopService(): Boolean {
        Log.d(TAG, "stopService() -> stopping EnforcementForegroundService lock monitor")
        return try {
            val intent = Intent(context, EnforcementForegroundService::class.java).apply {
                action = EnforcementForegroundService.ACTION_STOP
                putExtra(EnforcementForegroundService.EXTRA_STOP_LOCK_MONITOR, true)
            }
            context.startService(intent)
            eventPublisher.emitServiceStateChanged(Constants.SERVICE_STATUS_TRANSITIONING)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to stop foreground service", t)
            false
        }
    }

    fun isServiceRunning(): Boolean {
        Log.d(TAG, "isServiceRunning() -> ${EnforcementForegroundService.isLockMonitorRunning}")
        return EnforcementForegroundService.isLockMonitorRunning
    }
}
