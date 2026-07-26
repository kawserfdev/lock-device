package com.example.app_lock.compliance

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.app_lock.admin.LocationEnforcementController
import com.example.app_lock.util.Constants

/**
 * Process-global orchestrator of the Location Compliance Enforcement System.
 * Refactored to act as a lightweight facade delegating logic to extracted components.
 */
object LocationComplianceCoordinator {

    private const val TAG = "LocComplianceCoord"

    // The pre-compliance-screen grace delay is centralized in
    // ComplianceConfig.LOCATION_OFF_GRACE_PERIOD_MS (single source of truth). Change
    // that one value to adjust the delay everywhere in the project.
    // COMPLIANCE_LOCK_COUNTDOWN_MS is the countdown shown INSIDE the
    // compliance screen and is intentionally kept local (separate concern).
    const val COMPLIANCE_LOCK_COUNTDOWN_MS = 1 * 60 * 1000L
    const val ACTION_DISMISS_COMPLIANCE = "com.example.app_lock.action.DISMISS_COMPLIANCE"
    const val EXTRA_LOCK_DEADLINE_MS = "lock_deadline_ms"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var state: LocationComplianceState = LocationComplianceState.NORMAL

    private lateinit var store: LocationComplianceStateStore
    private lateinit var timerManager: ComplianceTimerManager
    private lateinit var uiManager: ComplianceUIManager
    private lateinit var serviceManager: ComplianceServiceManager

    val lockTaskController: LockTaskController
        get() = uiManager.lockTaskController

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        store = LocationComplianceStateStore(app)
        state = store.getState()

        timerManager = ComplianceTimerManager(
            onGraceTimeout = { onGraceCountdownElapsed() },
            onLockTimeout = { onLockCountdownElapsed() }
        )
        uiManager = ComplianceUIManager(app)
        serviceManager = ComplianceServiceManager(app)

        Log.i(TAG, "init() — restored persisted state=$state")
    }

    @Synchronized
    fun start(context: Context) {
        init(context)
        store.setEnforcementArmed(true)
        serviceManager.startService()
        evaluateInternal(LocationEnforcementController.getInstance(context), "start")
        Log.i(TAG, "start() — enforcement armed; state=$state")
    }

    @Synchronized
    fun stop() {
        Log.i(TAG, "stop() — disarming enforcement; was state=$state")
        timerManager.cancelAll()
        LocationComplianceUnlockReceiver.unregister(appContext)
        uiManager.dismissComplianceScreen()
        uiManager.releaseLockTask()
        serviceManager.stopService()
        if (::store.isInitialized) {
            store.clear()
        }
        transitionTo(ComplianceEvent.STOP, "stop")
    }

    @Synchronized
    fun onLocationDisabled() {
        if (!isArmed()) return
        if (state == LocationComplianceState.COUNTDOWN_RUNNING ||
            state == LocationComplianceState.COMPLIANCE_SCREEN ||
            state == LocationComplianceState.LOCKED
        ) {
            return
        }
        Log.i(TAG, "onLocationDisabled() — location turned OFF; starting grace countdown")
        transitionTo(ComplianceEvent.LOCATION_DISABLED_TRANSIENT, "location-disabled")
        startGraceCountdown()
    }

    @Synchronized
    fun onLocationEnabled() {
        if (state == LocationComplianceState.NORMAL) return
        if (!isArmed()) return
        Log.i(TAG, "onLocationEnabled() — location restored; cleaning up from state=$state")
        timerManager.cancelAll()
        LocationComplianceUnlockReceiver.unregister(appContext)
        // Broadcast DISMISS so any live compliance Activity finishes itself.
        // The Activity's onDestroy() then owns the FULL Lock Task release
        // sequence in the correct order: stopLockTask() THEN disarm().
        // We deliberately do NOT call uiManager.releaseLockTask() (disarm)
        // here anymore — disarming before stopLockTask() (which runs in the
        // Activity's onDestroy) clears the allowlist while still pinned,
        // which can leave the pin in an inconsistent state. The Activity is
        // the single owner of the stopLockTask()+disarm() sequence.
        uiManager.dismissComplianceScreen()
        store.resetLockIteration()
        transitionTo(ComplianceEvent.LOCATION_RESTORED_TRANSIENT, "location-enabled")
        transitionTo(ComplianceEvent.RESET_TO_NORMAL, "location-enabled")
    }

    @Synchronized
    private fun onGraceCountdownElapsed() {
        if (state != LocationComplianceState.COUNTDOWN_RUNNING) return
        Log.w(TAG, "onGraceCountdownElapsed() — grace expired; escalating to COMPLIANCE_SCREEN")
        transitionTo(ComplianceEvent.GRACE_TIMEOUT, "grace-elapsed")
        val lockDeadline = System.currentTimeMillis() + COMPLIANCE_LOCK_COUNTDOWN_MS
        store.setCountdownDeadline(lockDeadline)
        // Arm Lock Task Mode BEFORE launching the activity so the allowlist is ready immediately.
        lockTaskController.arm()
        uiManager.launchComplianceActivity(lockDeadline)
        timerManager.startLockTimer(COMPLIANCE_LOCK_COUNTDOWN_MS)
    }

    @Synchronized
    private fun onLockCountdownElapsed() {
        if (state != LocationComplianceState.COMPLIANCE_SCREEN) return
        Log.w(TAG, "onLockCountdownElapsed() — lock countdown expired; locking device")
        escalateToLocked("lock-countdown-elapsed")
    }

    @Synchronized
    fun onComplianceScreenDismissed() {
        if (state != LocationComplianceState.COMPLIANCE_SCREEN) return
        Log.w(TAG, "onComplianceScreenDismissed() — screen dismissed; locking")
        escalateToLocked("screen-dismissed")
    }

    @Synchronized
    fun onUserUnlocked() {
        if (state != LocationComplianceState.LOCKED) return
        LocationComplianceUnlockReceiver.unregister(appContext)
        val controller = appContext?.let { LocationEnforcementController.getInstance(it) }
        val locationOn = controller?.isLocationEnabled() == true
        Log.i(TAG, "onUserUnlocked() — location on=$locationOn")
        if (locationOn) {
            store.resetLockIteration()
            transitionTo(ComplianceEvent.LOCATION_RESTORED_TRANSIENT, "unlocked-location-on")
            transitionTo(ComplianceEvent.RESET_TO_NORMAL, "unlocked-location-on")
        } else {
            Log.w(TAG, "onUserUnlocked() — location still OFF; restarting grace countdown")
            transitionTo(ComplianceEvent.USER_UNLOCKED_LOCATION_OFF, "unlocked-location-off")
            startGraceCountdown()
        }
    }

    @Synchronized
    fun evaluateAfterApply(controller: LocationEnforcementController) {
        evaluateInternal(controller, "worker-tick")
    }

    @Synchronized
    fun restore(context: Context) {
        init(context)
        if (!store.isEnforcementArmed()) {
            transitionTo(ComplianceEvent.STOP, "restore-not-armed")
            return
        }
        Log.i(TAG, "restore() — Reconciliation; persisted state=${store.getState()}")
        state = store.getState()
        evaluateInternal(LocationEnforcementController.getInstance(context), "restore")
    }

    @Synchronized
    private fun evaluateInternal(controller: LocationEnforcementController, reason: String) {
        val locationOn = controller.isLocationEnabled()
        if (locationOn) {
            if (state != LocationComplianceState.NORMAL) {
                timerManager.cancelAll()
                LocationComplianceUnlockReceiver.unregister(appContext)
                // Broadcast DISMISS so any live compliance Activity finishes and
                // its onDestroy() owns the FULL release: stopLockTask() THEN
                // disarm(). We do NOT call releaseLockTask() (disarm) here:
                // disarming while an Activity is still pinned (before its
                // stopLockTask() runs) clears the allowlist mid-pin and can
                // leave Lock Task Mode inconsistent. If no Activity is live
                // (e.g. restore() after process death), the stale allowlist is
                // harmless — it only PERMITS Lock Task, it never forces it —
                // and is reset on the next arm() cycle.
                uiManager.dismissComplianceScreen()
                store.resetLockIteration()
                transitionTo(ComplianceEvent.LOCATION_RESTORED_TRANSIENT, "$reason-location-on")
                transitionTo(ComplianceEvent.RESET_TO_NORMAL, "$reason-location-on")
            }
            return
        }

        when (state) {
            LocationComplianceState.NORMAL,
            LocationComplianceState.LOCATION_OFF,
            LocationComplianceState.LOCATION_RESTORED -> {
                transitionTo(ComplianceEvent.LOCATION_DISABLED_TRANSIENT, "$reason-location-off")
                startGraceCountdown()
            }
            LocationComplianceState.COUNTDOWN_RUNNING -> {
                val deadline = store.getCountdownDeadline()
                if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                    onGraceCountdownElapsed()
                } else if (deadline == 0L) {
                    startGraceCountdown()
                } else {
                    val delay = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
                    timerManager.startGraceTimer(delay)
                }
            }
            LocationComplianceState.COMPLIANCE_SCREEN -> {
                val deadline = store.getCountdownDeadline()
                if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                    onLockCountdownElapsed()
                } else {
                    val lockDeadline = if (deadline > 0) deadline else System.currentTimeMillis() + COMPLIANCE_LOCK_COUNTDOWN_MS
                    store.setCountdownDeadline(lockDeadline)
                    // Arm Lock Task Mode BEFORE launching the activity so the allowlist is ready immediately.
                    lockTaskController.arm()
                    uiManager.launchComplianceActivity(lockDeadline)
                    val delay = (lockDeadline - System.currentTimeMillis()).coerceAtLeast(0L)
                    timerManager.startLockTimer(delay)
                }
            }
            LocationComplianceState.LOCKED -> {
                LocationComplianceUnlockReceiver.register(appContext)
            }
        }
    }

    @Synchronized
    private fun startGraceCountdown() {
        timerManager.cancelAll()
        val deadline = System.currentTimeMillis() + ComplianceConfig.LOCATION_OFF_GRACE_PERIOD_MS
        store.setCountdownDeadline(deadline)
        transitionTo(ComplianceEvent.START_GRACE_COUNTDOWN, "start-grace")
        timerManager.startGraceTimer(ComplianceConfig.LOCATION_OFF_GRACE_PERIOD_MS)
    }

    @Synchronized
    private fun escalateToLocked(reason: String) {
        timerManager.cancelAll()
        val controller = appContext?.let { LocationEnforcementController.getInstance(it) }
        val locked = controller?.lockNow() == true
        val iteration = store.incrementLockIteration()
        Log.w(TAG, "escalateToLocked($reason) — lockNow=$locked, iteration=$iteration")
        // Broadcast DISMISS so the live compliance Activity finishes and its
        // onDestroy() owns the FULL release: stopLockTask() THEN disarm().
        // We do NOT call releaseLockTask() (disarm) here — disarming before the
        // Activity's stopLockTask() clears the allowlist while still pinned.
        uiManager.dismissComplianceScreen()
        if (locked) {
            LocationComplianceUnlockReceiver.register(appContext)
            transitionTo(ComplianceEvent.LOCK_TIMEOUT, reason)
        } else {
            // lockNow() failed: re-launch the compliance Activity. The new
            // Activity's onCreate() -> startLockTaskMode() re-arms (allowlist +
            // features) and re-pins. The old Activity's onDestroy() release is
            // idempotent (lockTaskReleased flag + disarm no-op if already cleared).
            val lockDeadline = System.currentTimeMillis() + COMPLIANCE_LOCK_COUNTDOWN_MS
            store.setCountdownDeadline(lockDeadline)
            uiManager.launchComplianceActivity(lockDeadline)
            transitionTo(ComplianceEvent.SCREEN_DISMISSED, "$reason-lock-failed") // fallback / loop
            timerManager.startLockTimer(COMPLIANCE_LOCK_COUNTDOWN_MS)
        }
    }

    @Synchronized
    private fun transitionTo(event: ComplianceEvent, reason: String) {
        val newState = ComplianceStateMachine.transition(state, event)
        if (state == newState) return
        Log.i(TAG, "transition: $state → $newState ($reason)")
        state = newState
        if (::store.isInitialized) {
            store.setState(newState)
        }
    }

    @Synchronized
    fun isArmed(): Boolean = ::store.isInitialized && store.isEnforcementArmed()

    fun getState(): LocationComplianceState = state
    fun stateName(): String = state.name

    @Synchronized
    fun isComplianceScreenVisible(): Boolean = state == LocationComplianceState.COMPLIANCE_SCREEN

    @Synchronized
    fun getCountdownRemainingMs(): Long {
        if (!::store.isInitialized) return 0L
        val deadline = store.getCountdownDeadline()
        if (deadline <= 0L) return 0L
        return (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    @Synchronized
    fun getLockIteration(): Int = if (::store.isInitialized) store.getLockIteration() else 0

    fun isLocationEnabled(): Boolean {
        val ctx = appContext ?: return false
        return LocationEnforcementController.getInstance(ctx).isLocationEnabled()
    }
}
