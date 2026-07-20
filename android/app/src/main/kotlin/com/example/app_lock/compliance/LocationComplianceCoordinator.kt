package com.example.app_lock.compliance

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.app_lock.admin.LocationEnforcementController

/**
 * Process-global orchestrator of the Location Compliance Enforcement System.
 *
 * Owns the [LocationComplianceState] machine, the grace + lock countdowns, the
 * escalation ladder (compliance screen → `lockNow()` → unlock loop), and the
 * lifecycle of the [LocationComplianceForegroundService] and the
 * [LocationComplianceUnlockReceiver].
 *
 * ## Enforcement ladder
 *
 * When the user disables Device Location while the Installment Locker's
 * "Uninstall Block" protection is active, the coordinator escalates:
 *
 * 1. **[LocationComplianceState.LOCATION_OFF]** — transient; immediately
 *    becomes [LocationComplianceState.COUNTDOWN_RUNNING].
 * 2. **[LocationComplianceState.COUNTDOWN_RUNNING]** — a [GRACE_PERIOD_MS]
 *    (2-minute) grace countdown runs with no UI. If the user re-enables
 *    location in time → [LocationComplianceState.LOCATION_RESTORED] →
 *    [LocationComplianceState.NORMAL].
 * 3. **[LocationComplianceState.COMPLIANCE_SCREEN]** — on grace expiry, the
 *    full-screen non-dismissible [LocationComplianceActivity] is launched and
 *    the device is pinned via Lock Task Mode. A second
 *    ([COMPLIANCE_LOCK_COUNTDOWN_MS]) countdown is shown on the screen; if it
 *    expires with location still off, the device is locked.
 * 4. **[LocationComplianceState.LOCKED]** — `DevicePolicyManager.lockNow()`
 *    fires and the [LocationComplianceUnlockReceiver] is armed. When the user
 *    unlocks, location is re-verified: if ON → restored; if OFF → back to
 *    [LocationComplianceState.COUNTDOWN_RUNNING] and the loop repeats.
 *
 * This loop repeats **indefinitely** until the user enables location, ensuring
 * the device can never remain untrackable while the protection is active.
 *
 * ## Persistence & restore
 *
 * Every state transition is persisted via [LocationComplianceStateStore] so the
 * system survives reboot, process death, and service restart. [restore] and
 * [evaluateAfterApply] reconcile the persisted state with the live location
 * setting and resume the appropriate step — e.g. a persisted
 * [LocationComplianceState.COUNTDOWN_RUNNING] whose deadline already elapsed
 * is escalated straight to [LocationComplianceState.COMPLIANCE_SCREEN].
 *
 * ## Thread-safety
 *
 * A singleton `object`. All mutating methods are `@Synchronized`; the state
 * field is `@Volatile` for lock-free reads by the worker / activity. The
 * countdown [Handler] runs on the main looper so UI launches happen on the
 * platform thread.
 *
 * ## Lifecycle owners
 *
 * - [start] is called by [LocationEnforcementController.startRealtimeWatch]
 *   (which is called from [com.example.app_lock.admin.AppLockDeviceAdminReceiver]
 *   and [com.example.app_lock.admin.UninstallBlockController] when the block is
 *   turned ON).
 * - [stop] is called by [LocationEnforcementController.stopRealtimeWatch] when
 *   the block is turned OFF.
 * - [onLocationDisabled] / [onLocationEnabled] are called by
 *   [com.example.app_lock.admin.LocationProviderChangeReceiver].
 * - [evaluateAfterApply] is called by
 *   [com.example.app_lock.admin.LocationEnforcementWorker] on each 15-min tick.
 * - [restore] is called by [LocationComplianceForegroundService.onStartCommand]
 *   and [LocationComplianceBootReceiver.onReceive].
 * - [onUserUnlocked] is called by [LocationComplianceUnlockReceiver].
 * - [onComplianceScreenDismissed] is called by [LocationComplianceActivity.onDestroy].
 */
object LocationComplianceCoordinator {

    private const val TAG = "LocComplianceCoord"

    /**
     * Grace period (ms) the user has to re-enable location before the
     * compliance screen appears. Two minutes, per spec.
     */
    const val GRACE_PERIOD_MS = 2 * 60 * 1000L

    /**
     * Countdown (ms) shown on the compliance screen before the device is
     * locked via `lockNow()`. Two minutes, matching the grace period so the
     * user has a consistent window at every escalation step.
     */
    const val COMPLIANCE_LOCK_COUNTDOWN_MS = 2 * 60 * 1000L

    /**
     * Broadcast action sent to the compliance Activity to tell it to finish
     * itself (used when location is restored while the screen is visible).
     */
    const val ACTION_DISMISS_COMPLIANCE = "com.example.app_lock.action.DISMISS_COMPLIANCE"

    /** Extra on the compliance Activity launch intent: the lock countdown deadline. */
    const val EXTRA_LOCK_DEADLINE_MS = "lock_deadline_ms"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var state: LocationComplianceState = LocationComplianceState.NORMAL

    private lateinit var store: LocationComplianceStateStore

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Fires when the grace countdown expires → COMPLIANCE_SCREEN. */
    private val graceCountdownRunnable = Runnable { onGraceCountdownElapsed() }

    /** Fires when the compliance-screen lock countdown expires → LOCKED. */
    private val lockCountdownRunnable = Runnable { onLockCountdownElapsed() }

    /**
     * Initializes the coordinator with the application context.
     *
     * Idempotent — safe to call from the receiver, the worker, the foreground
     * service, and the boot receiver. The first call wins; subsequent calls
     * are no-ops (the app context is process-singleton anyway).
     */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        store = LocationComplianceStateStore(context)
        state = store.getState()
        Log.i(TAG, "init() — restored persisted state=$state")
    }

    /**
     * Arms the enforcement system.
     *
     * Called when the "Uninstall Block" is turned ON. Marks enforcement armed
     * in the store, starts the compliance foreground service (to keep the
     * process alive across screen-off / app-swipe), and evaluates the current
     * location state to resume any in-progress enforcement.
     *
     * Idempotent.
     */
    @Synchronized
    fun start(context: Context) {
        init(context)
        store.setEnforcementArmed(true)
        startForegroundService(context)
        // Resume enforcement based on the live location state.
        evaluateInternal(LocationEnforcementController(context), "start")
        Log.i(TAG, "start() — enforcement armed; state=$state")
    }

    /**
     * Disarms the enforcement system.
     *
     * Called when the "Uninstall Block" is turned OFF (before the Device Owner
     * role is cleared). Cancels any in-progress countdown, dismisses the
     * compliance screen, disarms the unlock receiver, stops the foreground
     * service, and clears the persisted state so a stale state cannot trigger
     * enforcement after a reboot.
     *
     * Idempotent.
     */
    @Synchronized
    fun stop() {
        Log.i(TAG, "stop() — disarming enforcement; was state=$state")
        cancelCountdowns()
        LocationComplianceUnlockReceiver.unregister(appContext)
        dismissComplianceScreen()
        LocationComplianceActivity.releaseLockTask(appContext)
        stopForegroundService()
        if (::store.isInitialized) {
            store.clear()
        }
        transitionTo(LocationComplianceState.NORMAL, "stop")
    }

    /**
     * Called by [LocationProviderChangeReceiver] when location is detected OFF.
     *
     * Transitions to [LocationComplianceState.LOCATION_OFF] then
     * [LocationComplianceState.COUNTDOWN_RUNNING] and starts the grace
     * countdown. No-op if enforcement is not armed or location is already on.
     */
    @Synchronized
    fun onLocationDisabled() {
        if (!isArmed()) {
            Log.d(TAG, "onLocationDisabled() — not armed, ignoring")
            return
        }
        if (state == LocationComplianceState.COUNTDOWN_RUNNING ||
            state == LocationComplianceState.COMPLIANCE_SCREEN ||
            state == LocationComplianceState.LOCKED
        ) {
            Log.d(TAG, "onLocationDisabled() — already enforcing (state=$state), ignoring")
            return
        }
        Log.i(TAG, "onLocationDisabled() — location turned OFF; starting grace countdown")
        transitionTo(LocationComplianceState.LOCATION_OFF, "location-disabled")
        startGraceCountdown()
    }

    /**
     * Called by [LocationProviderChangeReceiver] (and the compliance Activity
     * poller) when location is detected ON.
     *
     * Cancels any in-progress countdown, dismisses the compliance screen,
     * releases Lock Task Mode, disarms the unlock receiver, and transitions to
     * [LocationComplianceState.LOCATION_RESTORED] →
     * [LocationComplianceState.NORMAL]. No-op if already NORMAL.
     */
    @Synchronized
    fun onLocationEnabled() {
        if (state == LocationComplianceState.NORMAL) {
            Log.d(TAG, "onLocationEnabled() — already NORMAL, ignoring")
            return
        }
        if (!isArmed()) {
            Log.d(TAG, "onLocationEnabled() — not armed, ignoring")
            return
        }
        Log.i(TAG, "onLocationEnabled() — location restored; cleaning up from state=$state")
        cancelCountdowns()
        LocationComplianceUnlockReceiver.unregister(appContext)
        dismissComplianceScreen()
        LocationComplianceActivity.releaseLockTask(appContext)
        store.resetLockIteration()
        transitionTo(LocationComplianceState.LOCATION_RESTORED, "location-enabled")
        transitionTo(LocationComplianceState.NORMAL, "location-enabled")
    }

    /**
     * Called by the grace countdown [Handler] when [GRACE_PERIOD_MS] elapses
     * with location still off.
     *
     * Escalates to [LocationComplianceState.COMPLIANCE_SCREEN]: launches the
     * compliance Activity and starts the lock countdown shown on the screen.
     */
    @Synchronized
    private fun onGraceCountdownElapsed() {
        if (state != LocationComplianceState.COUNTDOWN_RUNNING) {
            Log.d(TAG, "onGraceCountdownElapsed() — state=$state, ignoring stale callback")
            return
        }
        Log.w(TAG, "onGraceCountdownElapsed() — grace expired; escalating to COMPLIANCE_SCREEN")
        transitionTo(LocationComplianceState.COMPLIANCE_SCREEN, "grace-elapsed")
        val lockDeadline = System.currentTimeMillis() + COMPLIANCE_LOCK_COUNTDOWN_MS
        store.setCountdownDeadline(lockDeadline)
        launchComplianceActivity(lockDeadline)
        startLockCountdown()
    }

    /**
     * Called by the lock countdown [Handler] when
     * [COMPLIANCE_LOCK_COUNTDOWN_MS] elapses with location still off while the
     * compliance screen is visible.
     *
     * Escalates to [LocationComplianceState.LOCKED]: calls `lockNow()`, arms
     * the unlock receiver, and dismisses the compliance screen (the lock
     * screen replaces it).
     */
    @Synchronized
    private fun onLockCountdownElapsed() {
        if (state != LocationComplianceState.COMPLIANCE_SCREEN) {
            Log.d(TAG, "onLockCountdownElapsed() — state=$state, ignoring stale callback")
            return
        }
        Log.w(TAG, "onLockCountdownElapsed() — lock countdown expired; locking device")
        escalateToLocked("lock-countdown-elapsed")
    }

    /**
     * Called by [LocationComplianceActivity.onDestroy] when the compliance
     * screen is destroyed **without** location being enabled (e.g. the user
     * pressed Home, the screen was swiped away, or the OS killed the activity).
     *
     * Escalates to [LocationComplianceState.LOCKED] so the device is locked and
     * the unlock loop begins. Idempotent — a no-op if the state is no longer
     * [LocationComplianceState.COMPLIANCE_SCREEN] (e.g. location was restored
     * just before the activity died).
     */
    @Synchronized
    fun onComplianceScreenDismissed() {
        if (state != LocationComplianceState.COMPLIANCE_SCREEN) {
            Log.d(TAG, "onComplianceScreenDismissed() — state=$state, ignoring (already resolved)")
            return
        }
        Log.w(TAG, "onComplianceScreenDismissed() — screen dismissed without enabling location; locking")
        escalateToLocked("screen-dismissed")
    }

    /**
     * Called by [LocationComplianceUnlockReceiver] when the user unlocks the
     * device (ACTION_USER_PRESENT) while in the [LocationComplianceState.LOCKED]
     * state.
     *
     * Re-verifies location:
     * - **ON** → [LocationComplianceState.LOCATION_RESTORED] →
     *   [LocationComplianceState.NORMAL] (loop ends).
     * - **OFF** → [LocationComplianceState.COUNTDOWN_RUNNING] with a fresh
     *   grace countdown (loop continues).
     */
    @Synchronized
    fun onUserUnlocked() {
        if (state != LocationComplianceState.LOCKED) {
            Log.d(TAG, "onUserUnlocked() — state=$state, ignoring (not LOCKED)")
            return
        }
        LocationComplianceUnlockReceiver.unregister(appContext)
        val controller = appContext?.let { LocationEnforcementController(it) }
        val locationOn = controller?.isLocationEnabled() == true
        Log.i(TAG, "onUserUnlocked() — location on=$locationOn")
        if (locationOn) {
            store.resetLockIteration()
            transitionTo(LocationComplianceState.LOCATION_RESTORED, "unlocked-location-on")
            transitionTo(LocationComplianceState.NORMAL, "unlocked-location-on")
        } else {
            Log.w(TAG, "onUserUnlocked() — location still OFF; restarting grace countdown")
            transitionTo(LocationComplianceState.LOCATION_OFF, "unlocked-location-off")
            startGraceCountdown()
        }
    }

    /**
     * Called by [LocationEnforcementWorker] after each periodic re-apply tick
     * to reconcile the persisted state with the live location setting.
     *
     * This is the self-healing path after process death: the worker re-applies
     * the forced-on setting, re-registers the real-time receiver, then calls
     * here so the coordinator can resume any in-progress enforcement that was
     * lost when the process was killed.
     *
     * Reconciliation rules:
     * - Location ON → ensure [LocationComplianceState.NORMAL] (cancel any
     *   stale countdown / screen).
     * - Location OFF + state NORMAL/LOCATION_RESTORED → start grace countdown.
     * - Location OFF + state COUNTDOWN_RUNNING + deadline elapsed → escalate
     *   to COMPLIANCE_SCREEN.
     * - Location OFF + state COMPLIANCE_SCREEN + deadline elapsed → escalate
     *   to LOCKED.
     * - Location OFF + state LOCKED → re-arm unlock receiver (the receiver may
     *   have been lost with the process).
     */
    @Synchronized
    fun evaluateAfterApply(controller: LocationEnforcementController) {
        evaluateInternal(controller, "worker-tick")
    }

    /**
     * Rebuilds the coordinator state from the persisted store.
     *
     * Called by [LocationComplianceForegroundService.onStartCommand] (on
     * START_STICKY restart) and [LocationComplianceBootReceiver.onReceive]
     * (after reboot). Reads the persisted state and, if enforcement was armed,
     * re-evaluates against the live location setting to resume the right step.
     */
    @Synchronized
    fun restore(context: Context) {
        init(context)
        if (!store.isEnforcementArmed()) {
            Log.d(TAG, "restore() — enforcement not armed, staying idle")
            transitionTo(LocationComplianceState.NORMAL, "restore-not-armed")
            return
        }
        Log.i(TAG, "restore() — enforcement armed; reconciling persisted state=${store.getState()}")
        // Seed the in-memory state from the store before evaluating, so
        // evaluateInternal sees the persisted state rather than the default.
        state = store.getState()
        evaluateInternal(LocationEnforcementController(context), "restore")
    }

    /**
     * Internal reconciliation engine shared by [start], [evaluateAfterApply],
     * and [restore].
     */
    @Synchronized
    private fun evaluateInternal(controller: LocationEnforcementController, reason: String) {
        val locationOn = controller.isLocationEnabled()
        Log.d(TAG, "evaluateInternal($reason) — locationOn=$locationOn, state=$state")
        if (locationOn) {
            if (state != LocationComplianceState.NORMAL) {
                cancelCountdowns()
                LocationComplianceUnlockReceiver.unregister(appContext)
                dismissComplianceScreen()
                LocationComplianceActivity.releaseLockTask(appContext)
                store.resetLockIteration()
                transitionTo(LocationComplianceState.LOCATION_RESTORED, "$reason-location-on")
                transitionTo(LocationComplianceState.NORMAL, "$reason-location-on")
            }
            return
        }
        // Location is OFF — resume / start enforcement.
        when (state) {
            LocationComplianceState.NORMAL,
            LocationComplianceState.LOCATION_OFF,
            LocationComplianceState.LOCATION_RESTORED,
            -> {
                Log.i(TAG, "evaluateInternal($reason) — location OFF from $state; starting grace countdown")
                transitionTo(LocationComplianceState.LOCATION_OFF, "$reason-location-off")
                startGraceCountdown()
            }
            LocationComplianceState.COUNTDOWN_RUNNING -> {
                val deadline = store.getCountdownDeadline()
                if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                    Log.w(TAG, "evaluateInternal($reason) — grace deadline already elapsed; escalating")
                    onGraceCountdownElapsed()
                } else if (deadline == 0L) {
                    // Persisted state has no deadline (e.g. process died
                    // mid-transition) — restart the grace countdown fresh.
                    Log.w(TAG, "evaluateInternal($reason) — no deadline stored; restarting grace countdown")
                    startGraceCountdown()
                } else {
                    // Deadline is in the future — re-arm the Handler to fire at it.
                    Log.d(TAG, "evaluateInternal($reason) — re-arming grace countdown to fire at deadline")
                    mainHandler.removeCallbacks(graceCountdownRunnable)
                    val delay = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
                    mainHandler.postDelayed(graceCountdownRunnable, delay)
                }
            }
            LocationComplianceState.COMPLIANCE_SCREEN -> {
                val deadline = store.getCountdownDeadline()
                if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                    Log.w(TAG, "evaluateInternal($reason) — lock deadline already elapsed; locking")
                    onLockCountdownElapsed()
                } else {
                    // Re-launch the compliance screen (it was lost with the
                    // process) and re-arm the lock countdown.
                    Log.i(TAG, "evaluateInternal($reason) — re-launching compliance screen")
                    val lockDeadline = if (deadline > 0) deadline
                    else System.currentTimeMillis() + COMPLIANCE_LOCK_COUNTDOWN_MS
                    store.setCountdownDeadline(lockDeadline)
                    launchComplianceActivity(lockDeadline)
                    mainHandler.removeCallbacks(lockCountdownRunnable)
                    val delay = (lockDeadline - System.currentTimeMillis()).coerceAtLeast(0L)
                    mainHandler.postDelayed(lockCountdownRunnable, delay)
                }
            }
            LocationComplianceState.LOCKED -> {
                // Re-arm the unlock receiver (lost with the process).
                Log.i(TAG, "evaluateInternal($reason) — re-arming unlock receiver for LOCKED state")
                LocationComplianceUnlockReceiver.register(appContext)
            }
        }
    }

    /**
     * Starts the grace countdown: sets the persisted deadline and posts the
     * [graceCountdownRunnable] to the main [Handler].
     */
    @Synchronized
    private fun startGraceCountdown() {
        mainHandler.removeCallbacks(graceCountdownRunnable)
        mainHandler.removeCallbacks(lockCountdownRunnable)
        val deadline = System.currentTimeMillis() + GRACE_PERIOD_MS
        store.setCountdownDeadline(deadline)
        transitionTo(LocationComplianceState.COUNTDOWN_RUNNING, "start-grace")
        mainHandler.postDelayed(graceCountdownRunnable, GRACE_PERIOD_MS)
        Log.i(TAG, "startGraceCountdown() — grace countdown armed (${GRACE_PERIOD_MS}ms); deadline=$deadline")
    }

    /**
     * Starts the lock countdown (shown on the compliance screen): posts the
     * [lockCountdownRunnable] to fire after [COMPLIANCE_LOCK_COUNTDOWN_MS].
     */
    @Synchronized
    private fun startLockCountdown() {
        mainHandler.removeCallbacks(lockCountdownRunnable)
        mainHandler.postDelayed(lockCountdownRunnable, COMPLIANCE_LOCK_COUNTDOWN_MS)
        Log.i(TAG, "startLockCountdown() — lock countdown armed (${COMPLIANCE_LOCK_COUNTDOWN_MS}ms)")
    }

    /**
     * Cancels both countdowns and clears the persisted deadline.
     */
    @Synchronized
    private fun cancelCountdowns() {
        mainHandler.removeCallbacks(graceCountdownRunnable)
        mainHandler.removeCallbacks(lockCountdownRunnable)
        if (::store.isInitialized) {
            store.setCountdownDeadline(0L)
        }
    }

    /**
     * Escalates to [LocationComplianceState.LOCKED]: locks the device via
     * `lockNow()`, arms the unlock receiver, dismisses the compliance screen,
     * and increments the lock-iteration counter.
     */
    @Synchronized
    private fun escalateToLocked(reason: String) {
        cancelCountdowns()
        val controller = appContext?.let { LocationEnforcementController(it) }
        val locked = controller?.lockNow() == true
        val iteration = store.incrementLockIteration()
        Log.w(TAG, "escalateToLocked($reason) — lockNow=$locked, iteration=$iteration")
        dismissComplianceScreen()
        LocationComplianceActivity.releaseLockTask(appContext)
        if (locked) {
            LocationComplianceUnlockReceiver.register(appContext)
            transitionTo(LocationComplianceState.LOCKED, reason)
        } else {
            // lockNow failed (e.g. admin inactive) — fall back to keeping the
            // compliance screen up. Re-launch it so the user still has a path
            // to enable location.
            Log.e(TAG, "escalateToLocked($reason) — lockNow failed; re-launching compliance screen")
            val lockDeadline = System.currentTimeMillis() + COMPLIANCE_LOCK_COUNTDOWN_MS
            store.setCountdownDeadline(lockDeadline)
            launchComplianceActivity(lockDeadline)
            transitionTo(LocationComplianceState.COMPLIANCE_SCREEN, "$reason-lock-failed")
            startLockCountdown()
        }
    }

    /**
     * Launches the full-screen compliance Activity with the lock countdown
     * deadline as an extra.
     *
     * Uses [Intent.FLAG_ACTIVITY_NEW_TASK] because the coordinator may be
     * running in a service / receiver context with no Activity task. Device
     * Owner apps are permitted to start activities from the background.
     */
    @Synchronized
    private fun launchComplianceActivity(lockDeadlineMs: Long) {
        val ctx = appContext ?: return
        runCatching {
            val intent = Intent(ctx, LocationComplianceActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                )
                putExtra(EXTRA_LOCK_DEADLINE_MS, lockDeadlineMs)
            }
            ctx.startActivity(intent)
            Log.i(TAG, "launchComplianceActivity() — compliance Activity launched")
        }.onFailure { Log.e(TAG, "launchComplianceActivity() — failed to start activity", it) }
    }

    /**
     * Broadcasts [ACTION_DISMISS_COMPLIANCE] so any visible compliance Activity
     * finishes itself. Best-effort — the Activity also self-dismisses via its
     * own location poller, so a missed broadcast is not fatal.
     */
    @Synchronized
    private fun dismissComplianceScreen() {
        val ctx = appContext ?: return
        runCatching {
            ctx.sendBroadcast(Intent(ACTION_DISMISS_COMPLIANCE).setPackage(ctx.packageName))
        }.onFailure { Log.w(TAG, "dismissComplianceScreen() — broadcast failed", it) }
    }

    /**
     * Starts the compliance foreground service (keeps the process alive so the
     * runtime PROVIDERS_CHANGED receiver and the coordinator survive screen-off
     * and app-swipe).
     */
    @Synchronized
    private fun startForegroundService(context: Context) {
        runCatching {
            val intent = Intent(context, LocationComplianceForegroundService::class.java)
            context.startService(intent)
        }.onFailure { Log.w(TAG, "startForegroundService() — failed", it) }
    }

    /**
     * Stops the compliance foreground service.
     */
    @Synchronized
    private fun stopForegroundService() {
        val ctx = appContext ?: return
        runCatching {
            ctx.stopService(Intent(ctx, LocationComplianceForegroundService::class.java))
        }.onFailure { Log.w(TAG, "stopForegroundService() — failed", it) }
    }

    /**
     * Persists and logs a state transition.
     */
    @Synchronized
    private fun transitionTo(newState: LocationComplianceState, reason: String) {
        if (state == newState) return
        Log.i(TAG, "transition: $state → $newState ($reason)")
        state = newState
        if (::store.isInitialized) {
            store.setState(newState)
        }
    }

    /**
     * Returns `true` when enforcement is armed (the "Uninstall Block" is ON).
     */
    @Synchronized
    fun isArmed(): Boolean =
        ::store.isInitialized && store.isEnforcementArmed()

    /**
     * Returns the current in-memory state. Lock-free read for the worker /
     * activity.
     */
    fun getState(): LocationComplianceState = state

    /**
     * Returns the current state name (for logging / Flutter event emission).
     */
    fun stateName(): String = state.name

    /**
     * Returns `true` when the compliance Activity is (believed to be) visible.
     */
    @Synchronized
    fun isComplianceScreenVisible(): Boolean =
        state == LocationComplianceState.COMPLIANCE_SCREEN

    /**
     * Returns the remaining milliseconds until the current countdown (grace or
     * lock) expires, or `0L` when no countdown is active.
     *
     * Used by [LocationComplianceActivity] to render the on-screen countdown.
     */
    @Synchronized
    fun getCountdownRemainingMs(): Long {
        if (!::store.isInitialized) return 0L
        val deadline = store.getCountdownDeadline()
        if (deadline <= 0L) return 0L
        return (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /**
     * Returns the persisted lock-iteration counter (for diagnostics / Flutter).
     */
    @Synchronized
    fun getLockIteration(): Int =
        if (::store.isInitialized) store.getLockIteration() else 0
}
