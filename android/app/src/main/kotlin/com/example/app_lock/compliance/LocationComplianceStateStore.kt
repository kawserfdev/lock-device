package com.example.app_lock.compliance

import android.content.Context
import android.util.Log
import androidx.core.content.edit

/**
 * Persistence layer for the [LocationComplianceState] machine.
 *
 * The Location Compliance Enforcement System must survive three classes of
 * disruption:
 *
 * 1. **Process death** — Android may kill the app process (low memory, the
 *    user swiping the app away, the OS trimming cached apps). The in-memory
 *    coordinator state is lost; the WorkManager worker and the foreground
 *    service restart restore it from this store.
 * 2. **Service restart** — `START_STICKY` services are restarted by the OS
 *    with a `null` intent after a kill. [EnforcementForegroundService]
 *    reads this store in `onStartCommand` to rebuild the coordinator state.
 * 3. **Device reboot** — a manifest-registered
 *    [LocationComplianceBootReceiver] fires on `BOOT_COMPLETED` and reads this
 *    store to decide whether to re-arm enforcement.
 *
 * ## What is persisted
 *
 * - **State** — the current [LocationComplianceState] (so the coordinator can
 *   resume the right step).
 * - **Countdown deadline** — the wall-clock millisecond timestamp at which the
 *   2-minute grace countdown expires. Wall-clock (not `SystemClock.elapsedRealTime`)
 *   is used because `elapsedRealTime` resets on reboot, which would make a
 *   reboot-during-countdown unrecoverable. The trade-off is that a manual
 *   clock change shifts the deadline; on an enterprise Device-Owner device
 *   this is acceptable, and the coordinator re-evaluates the deadline on every
 *   `PROVIDERS_CHANGED` broadcast and every worker tick anyway.
 * - **Lock iteration** — how many lock-then-verify cycles have run. Used for
 *   logging / diagnostics (e.g. "locked 3×, user still refusing to enable
 *   location") and to cap pathological loops in a future revision.
 * - **Enforcement armed flag** — whether the coordinator was started (so a
 *   restore knows whether to re-arm or stay idle, e.g. if the Installment
 *   Locker's "Uninstall Block" was turned OFF before the reboot).
 *
 * ## Storage
 *
 * A single private `SharedPreferences` file (`location_compliance_state.xml`).
 * Private mode so no other app can read or write the enforcement state.
 *
 * ## Thread-safety
 *
 * Every accessor is `@Synchronized` because the store is read and written from
 * the broadcast-delivery thread ([LocationProviderChangeReceiver]), the
 * WorkManager background thread ([LocationEnforcementWorker]), the foreground
 * service main thread, and the boot / unlock receivers. `SharedPreferences`
 * is itself thread-safe for reads/writes, but the read-modify-write of the
 * lock iteration counter needs an atomic guard, so the whole class is
 * serialized.
 *
 * ## Error handling
 *
 * All reads default to safe values on failure (state → [LocationComplianceState.NORMAL],
 * deadline → `0L`, iteration → `0`, armed → `false`) so a corrupt or
 * partially-written store can never wedge the enforcement system. Write
 * failures are logged but never thrown — losing a state write is recoverable
 * on the next tick.
 *
 * @param context Any context; the application context is used so the store is
 *   not tied to an Activity lifecycle.
 */
class LocationComplianceStateStore(context: Context) {

    companion object {
        private const val TAG = "LocComplianceStore"

        /** Private SharedPreferences file backing the store. */
        private const val PREFS_NAME = "location_compliance_state.xml"

        // ── Keys ────────────────────────────────────────────────────────────
        private const val KEY_STATE = "state"
        private const val KEY_COUNTDOWN_DEADLINE = "countdown_deadline_ms"
        private const val KEY_LOCK_ITERATION = "lock_iteration"
        private const val KEY_ENFORCEMENT_ARMED = "enforcement_armed"
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns the persisted [LocationComplianceState], or [LocationComplianceState.NORMAL]
     * when no state has ever been written (or the persisted value is corrupt).
     */
    @Synchronized
    fun getState(): LocationComplianceState {
        val name = runCatching { prefs.getString(KEY_STATE, null) }.getOrNull()
        val state = LocationComplianceState.fromName(name)
        Log.d(TAG, "getState() -> $state (raw='$name')")
        return state
    }

    /**
     * Persists [state]. Logs and swallows write failures — a lost write is
     * recoverable on the next tick.
     */
    @Synchronized
    fun setState(state: LocationComplianceState) {
        Log.d(TAG, "setState($state)")
        runCatching {
            prefs.edit { putString(KEY_STATE, state.name) }
        }.onFailure { Log.w(TAG, "setState($state) — write failed", it) }
    }

    /**
     * Returns the wall-clock millisecond timestamp at which the current
     * countdown expires, or `0L` when no countdown is active.
     *
     * Wall-clock ([System.currentTimeMillis]) is used instead of
     * [android.os.SystemClock.elapsedRealtime] because `elapsedRealtime`
     * resets on reboot, which would make a reboot-during-countdown
     * unrecoverable.
     */
    @Synchronized
    fun getCountdownDeadline(): Long {
        val deadline = runCatching { prefs.getLong(KEY_COUNTDOWN_DEADLINE, 0L) }
            .getOrDefault(0L)
        Log.d(TAG, "getCountdownDeadline() -> $deadline")
        return deadline
    }

    /**
     * Persists the countdown deadline. Pass `0L` to clear it.
     *
     * @param deadlineMs Wall-clock millisecond timestamp
     *   ([System.currentTimeMillis] based) at which the countdown expires.
     */
    @Synchronized
    fun setCountdownDeadline(deadlineMs: Long) {
        Log.d(TAG, "setCountdownDeadline($deadlineMs)")
        runCatching {
            prefs.edit { putLong(KEY_COUNTDOWN_DEADLINE, deadlineMs) }
        }.onFailure { Log.w(TAG, "setCountdownDeadline($deadlineMs) — write failed", it) }
    }

    /**
     * Returns how many lock-then-verify cycles have run so far (0 on first
     * lock). Used for diagnostics and to cap pathological loops.
     */
    @Synchronized
    fun getLockIteration(): Int {
        val iter = runCatching { prefs.getInt(KEY_LOCK_ITERATION, 0) }.getOrDefault(0)
        Log.d(TAG, "getLockIteration() -> $iter")
        return iter
    }

    /**
     * Atomically increments and returns the new lock-iteration counter.
     *
     * Atomicity is guaranteed by the `@Synchronized` guard on this class —
     * the read-modify-write is not interruptible by another thread.
     */
    @Synchronized
    fun incrementLockIteration(): Int {
        val next = (runCatching { prefs.getInt(KEY_LOCK_ITERATION, 0) }.getOrDefault(0)) + 1
        Log.d(TAG, "incrementLockIteration() -> $next")
        runCatching {
            prefs.edit { putInt(KEY_LOCK_ITERATION, next) }
        }.onFailure { Log.w(TAG, "incrementLockIteration() — write failed", it) }
        return next
    }

    /**
     * Resets the lock-iteration counter to `0`. Called when location is
     * restored (the loop has ended) so the next enforcement cycle starts fresh.
     */
    @Synchronized
    fun resetLockIteration() {
        Log.d(TAG, "resetLockIteration()")
        runCatching {
            prefs.edit { putInt(KEY_LOCK_ITERATION, 0) }
        }.onFailure { Log.w(TAG, "resetLockIteration() — write failed", it) }
    }

    /**
     * Returns `true` when the coordinator was started (enforcement armed).
     *
     * A restore uses this to decide whether to re-arm enforcement or stay
     * idle — e.g. if the Installment Locker's "Uninstall Block" was turned OFF
     * before a reboot, this is `false` and the boot receiver does nothing.
     */
    @Synchronized
    fun isEnforcementArmed(): Boolean {
        val armed = runCatching { prefs.getBoolean(KEY_ENFORCEMENT_ARMED, false) }
            .getOrDefault(false)
        Log.d(TAG, "isEnforcementArmed() -> $armed")
        return armed
    }

    /**
     * Persists the enforcement-armed flag.
     *
     * Set `true` when the coordinator is started, `false` when it is stopped
     * (e.g. the "Uninstall Block" is turned OFF).
     */
    @Synchronized
    fun setEnforcementArmed(armed: Boolean) {
        Log.d(TAG, "setEnforcementArmed($armed)")
        runCatching {
            prefs.edit { putBoolean(KEY_ENFORCEMENT_ARMED, armed) }
        }.onFailure { Log.w(TAG, "setEnforcementArmed($armed) — write failed", it) }
    }

    /**
     * Resets the entire store to the "no enforcement" baseline.
     *
     * Called when the coordinator is stopped (the "Uninstall Block" is turned
     * OFF) so a stale persisted state cannot trigger enforcement after a
     * reboot. Leaves the enforcement-armed flag `false`.
     */
    @Synchronized
    fun clear() {
        Log.i(TAG, "clear() — resetting compliance state store")
        runCatching {
            prefs.edit {
                putString(KEY_STATE, LocationComplianceState.NORMAL.name)
                putLong(KEY_COUNTDOWN_DEADLINE, 0L)
                putInt(KEY_LOCK_ITERATION, 0)
                putBoolean(KEY_ENFORCEMENT_ARMED, false)
            }
        }.onFailure { Log.w(TAG, "clear() — write failed", it) }
    }
}
