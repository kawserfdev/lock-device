package com.example.app_lock.channels.handlers

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.app_lock.admin.DeviceAdminController
import com.example.app_lock.channels.LockEventChannel
import com.example.app_lock.service.AppLockForegroundService
import com.example.app_lock.util.Constants

/**
 * Handles lock and service-control method calls coming from Flutter.
 *
 * This is a thin dispatcher: it routes each method name to the right native
 * collaborator ([DeviceAdminController] for locking, the foreground service
 * for start/stop) and translates the outcome into the primitive `Boolean`
 * that the MethodChannel contract expects.
 *
 * No business logic lives here — validation, retry, and the static OVERDUE
 * decision all live in [DeviceAdminController] / the service.
 */
class AppLockHandler(
    private val context: Context,
    private val eventChannel: LockEventChannel,
    private val lockEngine: DeviceAdminController,
) {

    companion object {
        private const val TAG = "AppLockHandler"
    }

    /**
     * Sends the lock command to the native lock engine.
     *
     * Delegates to [DeviceAdminController.lockNow], which performs validation
     * (admin active + not already locked) and emits a `lockResult` event.
     * Returns `true` only when the lock was actually executed.
     */
    fun lockNow(): Boolean {
        Log.d(TAG, "lockNow() -> delegating to DeviceAdminController")
        return lockEngine.lockNow()
    }

    /**
     * Sends the lock command with a custom lockout duration.
     *
     * Delegates to [DeviceAdminController.lockForDuration], which performs the
     * same validation as [lockNow] and opens a lockout window of [durationMs]
     * during which unlock attempts are immediately re-locked.
     *
     * @param durationMs How long (ms) the device must stay locked.
     * @return `true` only when the lock was actually executed.
     */
    fun lockForDuration(durationMs: Long): Boolean {
        Log.d(TAG, "lockForDuration($durationMs ms) -> delegating to DeviceAdminController")
        return lockEngine.lockForDuration(durationMs)
    }

    /**
     * Starts the foreground service.
     *
     * Builds the launch [Intent] and hands it to [Context.startForegroundService].
     * Returns `true` when the start intent was dispatched. The actual service
     * state is later confirmed via [isServiceRunning] or the
     * `serviceStateChanged` event.
     */
    fun startService(): Boolean {
        Log.d(TAG, "startService() -> launching AppLockForegroundService")
        return try {
            val intent = Intent(context, AppLockForegroundService::class.java)
            intent.action = AppLockForegroundService.ACTION_START
            context.startForegroundService(intent)
            eventChannel.emitServiceStateChanged(Constants.SERVICE_STATUS_TRANSITIONING)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start foreground service", t)
            false
        }
    }

    /**
     * Stops the foreground service.
     *
     * Returns `true` when the stop intent was dispatched.
     */
    fun stopService(): Boolean {
        Log.d(TAG, "stopService() -> stopping AppLockForegroundService")
        return try {
            val intent = Intent(context, AppLockForegroundService::class.java)
            intent.action = AppLockForegroundService.ACTION_STOP
            context.startService(intent)
            eventChannel.emitServiceStateChanged(Constants.SERVICE_STATUS_TRANSITIONING)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to stop foreground service", t)
            false
        }
    }

    /**
     * Returns `true` when the foreground service is currently running.
     *
     * The static [AppLockForegroundService.isRunning] flag is owned by the
     * service itself (set in `startForegroundService`, cleared in
     * `stopForegroundService` / `onDestroy`). It is accurate for the lifetime
     * of the current process, which is sufficient for the demo.
     */
    fun isServiceRunning(): Boolean {
        Log.d(TAG, "isServiceRunning() -> ${AppLockForegroundService.isRunning}")
        return AppLockForegroundService.isRunning
    }
}
