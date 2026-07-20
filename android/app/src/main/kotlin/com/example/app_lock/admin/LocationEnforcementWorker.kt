package com.example.app_lock.admin

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.app_lock.compliance.LocationComplianceCoordinator

/**
 * Periodic WorkManager worker that re-applies the forced location-on setting.
 *
 * ## Why a worker is needed
 *
 * Android provides **no** user-restriction that locks the location toggle ON
 * (the only location-related restriction, `DISALLOW_SHARE_LOCATION`, forces
 * location OFF). The user can therefore still toggle location off in Settings
 * even after [LocationEnforcementController.apply] sets it on. This worker
 * periodically re-enables location so the device stays trackable while the
 * Installment Locker's "Uninstall Block" protection is active.
 *
 * ## Lifecycle
 *
 * - **Enqueued** by [UninstallBlockController] when the block is turned ON
 *   (via [LocationEnforcementController.scheduleReapply]) as a periodic
 *   `PeriodicWorkRequest` with a 15-minute interval (WorkManager's minimum).
 * - **Cancelled** by [LocationEnforcementController.cancelReapply] when the
 *   block is turned OFF (or the Device Owner role is cleared).
 *
 * The worker is a thin wrapper: it constructs a
 * [LocationEnforcementController] with the application context, calls
 * [LocationEnforcementController.apply], **re-registers the real-time
 * [LocationProviderChangeReceiver]** so the watch self-heals after the app
 * process has been killed and the runtime receiver was lost, and then calls
 * [LocationComplianceCoordinator.evaluateAfterApply] so the compliance state
 * machine is reconciled with the live location setting — resuming any
 * in-progress enforcement (grace countdown, compliance screen, or the
 * lock-then-verify loop) that was lost when the process died.
 *
 * ## Why re-register here
 *
 * `android.location.PROVIDERS_CHANGED` cannot be registered in the manifest on
 * Android 8.0+ (it is not on the implicit-broadcast exception list), so the
 * receiver only lives while the app process is alive. When the OS kills the
 * process (low memory, app cached), the runtime receiver is lost — leaving a
 * gap until the next worker tick. By calling
 * [LocationEnforcementController.startRealtimeWatch] on every tick, the worker
 * restores the receiver as soon as WorkManager wakes the process, so the
 * real-time layer recovers within ~15 minutes of process death at worst.
 *
 * All capability checks (Device Owner + admin active) live in the controller,
 * so the worker is safe to run even after the Device Owner role has been
 * cleared — it simply becomes a no-op.
 *
 * ## Result
 *
 * Always returns [Result.success] — a failure to re-apply location is not a
 * reason to retry the worker (the next periodic run will try again), and
 * returning `failure` would cause WorkManager to apply back-off/exponential
 * retry semantics that are unnecessary here.
 */
class LocationEnforcementWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "LocationEnforceWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork() — re-applying forced location-on")
        val controller = LocationEnforcementController(applicationContext)
        val applied = controller.apply()
        // Re-register the real-time PROVIDERS_CHANGED receiver so the watch
        // self-heals after process death. Idempotent: a no-op if already
        // registered (e.g. the process never died). Guarded by isSupported()
        // inside startRealtimeWatch(), so it is a no-op after the Device Owner
        // role is cleared.
        controller.startRealtimeWatch()
        // Reconcile the compliance state machine with the live location
        // setting. This is the self-healing path after process death: if the
        // process was killed mid-enforcement (e.g. during a grace countdown or
        // while the compliance screen was showing), the coordinator resumes the
        // appropriate step. init() is idempotent; evaluateAfterApply() is a
        // no-op when enforcement is not armed.
        LocationComplianceCoordinator.init(applicationContext)
        LocationComplianceCoordinator.evaluateAfterApply(controller)
        Log.i(
            TAG,
            "doWork() — apply=$applied; location on=${controller.isLocationEnabled()}; " +
                "watchRegistered=${LocationProviderChangeReceiver.isRegistered()}; " +
                "complianceState=${LocationComplianceCoordinator.stateName()}",
        )
        // Always success: a transient failure is retried on the next periodic
        // tick; we do not want WorkManager's exponential back-off here.
        return Result.success()
    }
}
