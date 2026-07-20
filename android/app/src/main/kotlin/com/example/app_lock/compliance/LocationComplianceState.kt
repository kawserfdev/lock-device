package com.example.app_lock.compliance

/**
 * Finite-state machine for the Location Compliance Enforcement System.
 *
 * The Installment Locker must keep Device Location enabled while the
 * "Uninstall Block" protection is active so the device stays trackable. Android
 * exposes **no** user-restriction that locks the location toggle ON
 * (`DISALLOW_SHARE_LOCATION` does the *opposite* — it forces location OFF), so
 * the user can still toggle location off in Quick Settings or System Settings.
 * This state machine governs the escalating enforcement response when they do.
 *
 * ## States
 *
 * - [NORMAL] — Location is ON. No enforcement action is active. This is the
 *   resting state; the system stays here while location remains enabled.
 * - [LOCATION_OFF] — Location was just detected OFF (transient). The system
 *   immediately transitions to [COUNTDOWN_RUNNING]. This state exists only to
 *   make the "location went off" event explicit in logs and persisted state so
 *   a reboot / process-death restore can tell a fresh "off" event apart from a
 *   long-standing one.
 * - [COUNTDOWN_RUNNING] — The 2-minute grace countdown is in progress. If
 *   location is re-enabled before the deadline, the system transitions to
 *   [LOCATION_RESTORED] → [NORMAL]. If the deadline elapses with location still
 *   off, the system escalates to [COMPLIANCE_SCREEN].
 * - [COMPLIANCE_SCREEN] — The full-screen, non-dismissible
 *   [LocationComplianceActivity] is showing. The user is expected to open
 *   Location Settings and re-enable location. While in this state the device
 *   is *not* yet locked — the screen is the first escalation step. If location
 *   is re-enabled, the system transitions to [LOCATION_RESTORED] → [NORMAL].
 *   If the user dismisses the screen without enabling location (e.g. by
 *   powering off, or on a second compliance cycle after a lock), the system
 *   escalates to [LOCKED].
 * - [LOCKED] — The device has been locked via
 *   `DevicePolicyManager.lockNow()`. The compliance foreground service arms an
 *   `ACTION_USER_PRESENT` receiver; when the user unlocks, the system
 *   re-verifies location. If location is ON → [LOCATION_RESTORED] → [NORMAL].
 *   If location is still OFF → [COMPLIANCE_SCREEN] with a fresh 2-minute
 *   countdown, and the lock-then-verify loop repeats indefinitely until
 *   location is enabled.
 * - [LOCATION_RESTORED] — Location was re-enabled (transient). The system
 *   cleans up (dismisses the compliance screen, stops lock-task mode, clears
 *   the countdown) and transitions to [NORMAL].
 *
 * ## Persistence
 *
 * The current state is persisted by [LocationComplianceStateStore] so it
 * survives reboot, process death, and service restart. On restore, the
 * coordinator reconciles the persisted state with the live location setting
 * (see [LocationComplianceCoordinator.evaluateAfterApply]) and resumes the
 * appropriate enforcement step — e.g. a persisted [COUNTDOWN_RUNNING] whose
 * deadline has already elapsed is escalated straight to
 * [COMPLIANCE_SCREEN], and a persisted [LOCKED] re-arms the unlock receiver.
 *
 * ## Transitions (happy path)
 *
 * ```
 * NORMAL ──location off──> LOCATION_OFF ──> COUNTDOWN_RUNNING
 *   │                                          │
 *   │                                  deadline │ location still off
 *   │                                          ▼
 *   │                                     COMPLIANCE_SCREEN
 *   │                                          │
 *   │                                  screen dismissed / 2nd cycle
 *   │                                          ▼
 *   │                                         LOCKED
 *   │                                          │
 *   │                                  user unlocks, location still off
 *   │                                          ▼
 *   │                                     COMPLIANCE_SCREEN (new countdown)
 *   │
 *   └──── location enabled ──── LOCATION_RESTORED ◀─── (from any state)
 * ```
 *
 * ## Thread-safety
 *
 * The enum itself is immutable. State *transitions* are guarded by the
 * coordinator's `@Synchronized` methods; reads of the persisted state go
 * through the synchronized [LocationComplianceStateStore].
 */
enum class LocationComplianceState {

    /** Location is ON; no enforcement action is active. Resting state. */
    NORMAL,

    /**
     * Location was just detected OFF (transient). Immediately transitions to
     * [COUNTDOWN_RUNNING]. Persisted so a restore can distinguish a fresh
     * "off" event from a long-standing one.
     */
    LOCATION_OFF,

    /** The 2-minute grace countdown is in progress. */
    COUNTDOWN_RUNNING,

    /** The full-screen non-dismissible compliance Activity is showing. */
    COMPLIANCE_SCREEN,

    /** The device has been locked via `DevicePolicyManager.lockNow()`. */
    LOCKED,

    /**
     * Location was re-enabled (transient). Cleans up and transitions to
     * [NORMAL].
     */
    LOCATION_RESTORED,
    ;

    companion object {

        /**
         * Parses a persisted state name back into a [LocationComplianceState].
         *
         * Returns [NORMAL] when [name] is `null` or does not match any state —
         * this is the safe default for a corrupt / missing persisted value, so
         * a bad store entry can never wedge the enforcement system.
         *
         * @param name The persisted enum name (e.g. `"COUNTDOWN_RUNNING"`), or
         *   `null` when no state has ever been written.
         */
        fun fromName(name: String?): LocationComplianceState =
            entries.firstOrNull { it.name == name } ?: NORMAL
    }
}
