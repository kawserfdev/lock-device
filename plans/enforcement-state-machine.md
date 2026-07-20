# 🚦 Enforcement State Machine — Production Design

> **Scope:** Production-ready finite-state machine for the EnforcementEngine.
> **Constraint:** No code. Design only — state definitions, transition diagram, entry/exit actions, timeout handling, recovery, persistence.
> **Date:** 2026-07-07
> **Parent doc:** [`plans/enforcement-engine-design.md`](plans/enforcement-engine-design.md:1)

---

## Table of Contents

1. [Design Goals](#1-design-goals)
2. [State Definitions](#2-state-definitions)
3. [State Transition Diagram](#3-state-transition-diagram)
4. [Transition Table](#4-transition-table)
5. [Entry Actions](#5-entry-actions)
6. [Exit Actions](#6-exit-actions)
7. [Guard Conditions](#7-guard-conditions)
8. [Timeout Handling](#8-timeout-handling)
9. [Persistence](#9-persistence)
10. [Crash Recovery](#10-crash-recovery)
11. [Reboot Recovery](#11-reboot-recovery)
12. [State Invariants](#12-state-invariants)
13. [Recovery Decision Tree](#13-recovery-decision-tree)
14. [Event-to-Transition Cross-Reference](#14-event-to-transition-cross-reference)

---

## 1. Design Goals

| Goal | How this design achieves it |
|----------------------|-------------------------------------------------------------|
| **Auditable** | Every enforcement step is a discrete state — logs show exact state at any moment |
| **Recoverable** | Every state is persisted with wall-clock deadlines; crash/reboot reconciliation is deterministic |
| **Idempotent** | Re-entering a state re-runs entry actions safely (no duplicate timers, no duplicate locks) |
| **Single-threaded** | All transitions execute on the engine thread — no `@Synchronized` needed |
| **Testable** | Transitions are pure functions of `(state, event) → (newState, actions)` — unit-testable without Android |
| **No implicit loops** | The lock-verify loop is explicit across `LOCK_REQUIRED → LOCKED → DEVICE_UNLOCKED → VERIFYING → COMPLIANCE` |

### 1.1 Relationship to the Existing 6-State Machine

The existing [`LocationComplianceState`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceState.kt:81) enum has 6 states. This design **refines** it into 9 states by splitting the lock-then-verify loop:

| Old state (6) | New state (9) | Change |
|----------------------|-------------------------------|------------------|
| `NORMAL` | `NORMAL` | Unchanged |
| `LOCATION_OFF` | `LOCATION_DISABLED` | Renamed for clarity |
| `COUNTDOWN_RUNNING` | `COUNTDOWN_RUNNING` | Unchanged |
| `COMPLIANCE_SCREEN` | `COMPLIANCE` | Renamed |
| `LOCKED` | `LOCK_REQUIRED` + `LOCKED` + `DEVICE_UNLOCKED` + `VERIFYING` | **Split into 4 granular states** |
| `LOCATION_RESTORED` | `LOCATION_RESTORED` | Unchanged |

The split makes the lock-verify cycle fully auditable: instead of one opaque `LOCKED` state that means "device is locked, waiting for unlock, then will verify," the new machine tracks each phase separately so logs, persistence, and recovery logic can distinguish "about to lock" from "locked" from "just unlocked" from "checking location."

---

## 2. State Definitions

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        9 Enforcement States                              │
├──────────────────────┬──────────────────────────────────────────────────┤
│ NORMAL               │ Location ON. No enforcement active. Resting.     │
│ LOCATION_DISABLED    │ Location detected OFF. Grace timer started.      │
│                      │ Transient — auto-advances to COUNTDOWN_RUNNING.  │
│ COUNTDOWN_RUNNING    │ Grace countdown in progress. Compliance Activity  │
│                      │ showing. Lock task armed.                        │
│ COMPLIANCE           │ Grace expired, location still OFF. Lock countdown │
│                      │ running. User must re-enable location.           │
│ LOCK_REQUIRED        │ Decision to lock made. Entry calls lockNow().     │
│                      │ Transient — auto-advances to LOCKED.             │
│ LOCKED               │ Device locked via DPM. UserPresent receiver armed.│
│                      │ Waiting for user to unlock.                       │
│ DEVICE_UNLOCKED      │ User unlocked (ACTION_USER_PRESENT).              │
│                      │ Transient — auto-advances to VERIFYING.          │
│ VERIFYING            │ Querying location status post-unlock.            │
│                      │ If ON → LOCATION_RESTORED. If OFF → COMPLIANCE.  │
│ LOCATION_RESTORED    │ Location re-enabled. Cleanup in progress.        │
│                      │ Transient — auto-advances to NORMAL.             │
└──────────────────────┴──────────────────────────────────────────────────┘
```

### 2.1 State Classification

| State | Type | Duration | Persists? |
|----------------------|-------------|------------------|-----------|
| `NORMAL` | **Stable** | Indefinite | Yes |
| `LOCATION_DISABLED` | **Transient** | <1 ms (auto-advance) | Yes (for crash recovery) |
| `COUNTDOWN_RUNNING` | **Timed** | gracePeriodMs (120s) | Yes + deadline |
| `COMPLIANCE` | **Timed** | lockCountdownMs (120s) | Yes + deadline |
| `LOCK_REQUIRED` | **Transient** | <1 ms (auto-advance) | Yes (for crash recovery) |
| `LOCKED` | **Stable** | Indefinite (until user unlocks) | Yes |
| `DEVICE_UNLOCKED` | **Transient** | <1 ms (auto-advance) | Yes (for crash recovery) |
| `VERIFYING` | **Transient** | <100 ms (location probe) | Yes (for crash recovery) |
| `LOCATION_RESTORED` | **Transient** | <1 ms (auto-advance) | Yes (for crash recovery) |

**Transient states** exist to make the event-driven flow explicit in logs and persisted state. They auto-advance to the next state within the same engine cycle (the `PolicyManager` returns a `TransitionTo(nextState)` action immediately after the entry actions complete).

---

## 3. State Transition Diagram

### 3.1 Full Transition Diagram

```
                                    ┌──────────────────────────────────────────┐
                                    │                                          │
                                    ▼                                          │
                              ┌───────────┐                                   │
              ┌───────────────│  NORMAL   │──────────────────────────────┐    │
              │               └─────┬─────┘                               │    │
              │                     │                                     │    │
              │        LocationProviderChanged(OFF)                        │    │
              │     (guard: permissionGranted && enforcementArmed)        │    │
              │                     │                                     │    │
              │                     ▼                                     │    │
              │               ┌───────────────────┐                       │    │
              │               │ LOCATION_DISABLED │                       │    │
              │               └────────┬──────────┘                       │    │
              │                        │                                  │    │
              │                        │ (auto-advance)                   │    │
              │                        ▼                                  │    │
              │               ┌─────────────────────┐                     │    │
              │     ┌────────│ COUNTDOWN_RUNNING   │────────┐            │    │
              │     │         └──────────┬──────────┘       │            │    │
              │     │                    │                  │            │    │
              │     │    GracePeriodExpired              LocationProvider│    │
              │     │                    │             Changed(ON)      │    │
              │     │                    ▼                  │            │    │
              │     │           ┌──────────────┐            │            │    │
              │     │           │  COMPLIANCE  │            │            │    │
              │     │           └──────┬───────┘            │            │    │
              │     │                  │                    │            │    │
              │     │    LockCountdownExpired /             │            │    │
              │     │    ComplianceScreenDismissed          │            │    │
              │     │                  │                    │            │    │
              │     │                  ▼                    │            │    │
              │     │         ┌────────────────┐            │            │    │
              │     │         │  LOCK_REQUIRED │            │            │    │
              │     │         └───────┬────────┘            │            │    │
              │     │                 │                     │            │    │
              │     │                 │ (auto-advance       │            │    │
              │     │                 │  after lockNow())   │            │    │
              │     │                 ▼                     │            │    │
              │     │         ┌──────────────┐              │            │    │
              │     │         │   LOCKED     │              │            │    │
              │     │         └──────┬───────┘              │            │    │
              │     │                │                      │            │    │
              │     │                │ UserPresent          │            │    │
              │     │                │ (ACTION_USER_PRESENT)│            │    │
              │     │                ▼                      │            │    │
              │     │      ┌─────────────────────┐         │            │    │
              │     │      │  DEVICE_UNLOCKED    │         │            │    │
              │     │      └──────────┬──────────┘         │            │    │
              │     │                 │                     │            │    │
              │     │                 │ (auto-advance)      │            │    │
              │     │                 ▼                     │            │    │
              │     │         ┌──────────────┐              │            │    │
              │     │         │  VERIFYING   │              │            │    │
              │     │         └──┬────────┬──┘              │            │    │
              │     │   Location │        │ Location        │            │    │
              │     │   Enabled  │        │ StillDisabled   │            │    │
              │     │            │        │                 │            │    │
              │     │            │        └─────────────────┼────────────┼────┤
              │     │            │                          │            │    │
              │     │            │                          │            │    │
              │     │            ▼                          ▼            ▼    │
              │     │     ┌──────────────────────┐                          │
              └─────┼────▶│  LOCATION_RESTORED   │◀─────────────────────────┘
                    │     └──────────┬───────────┘
                    │                │
                    │                │ (auto-advance)
                    │                ▼
                    │           ┌───────────┐
                    └──────────│  NORMAL   │
                               └───────────┘
```

### 3.2 Simplified Flow (Happy Path)

```
NORMAL → LOCATION_DISABLED → COUNTDOWN_RUNNING → COMPLIANCE → LOCK_REQUIRED
  → LOCKED → DEVICE_UNLOCKED → VERIFYING → COMPLIANCE (loop if still off)
                                        → LOCATION_RESTORED → NORMAL (if on)
```

### 3.3 Short-Circuit Transitions (from any state)

```
┌─────────────────────────────────────────────────────────────────────────┐
│  From ANY state (except LOCK_REQUIRED, DEVICE_UNLOCKED, VERIFYING):     │
│                                                                         │
│  LocationProviderChanged(ON)  ──▶  LOCATION_RESTORED  ──▶  NORMAL       │
│                                                                         │
│  LockNowRequested             ──▶  LOCK_REQUIRED                        │
│                                                                         │
│  PermissionRevoked            ──▶  NORMAL (disarm enforcement)           │
│                                                                         │
│  EnforcementDisarmed          ──▶  NORMAL (stop all enforcement)        │
└─────────────────────────────────────────────────────────────────────────┘
```

> **Note:** `LOCK_REQUIRED`, `DEVICE_UNLOCKED`, and `VERIFYING` are transient states that complete within a single engine cycle. They cannot receive external events because the engine thread processes the auto-advance before yielding. The short-circuit transitions apply to the 6 non-transient states.

---

## 4. Transition Table

### 4.1 Primary Transitions

| # | From State | Event | Guard | To State | Actions |
|---|------------|-------|-------|----------|---------|
| T1 | `NORMAL` | `LocationProviderChanged(OFF)` | `permissionGranted && enforcementArmed` | `LOCATION_DISABLED` | Start grace timer, start service |
| T2 | `NORMAL` | `LockNowRequested` | — | `LOCK_REQUIRED` | — |
| T3 | `NORMAL` | `LockForDurationRequested(d)` | — | `LOCK_REQUIRED` | Store duration |
| T4 | `NORMAL` | `BootCompleted` | `enforcementArmed` | `NORMAL` | Re-arm watchers, start service |
| T5 | `NORMAL` | `PermissionRevoked` | — | `NORMAL` | Disarm enforcement |
| T6 | `LOCATION_DISABLED` | *(auto-advance)* | — | `COUNTDOWN_RUNNING` | Start compliance Activity, arm lock task |
| T7 | `LOCATION_DISABLED` | `LocationProviderChanged(ON)` | — | `LOCATION_RESTORED` | — |
| T8 | `COUNTDOWN_RUNNING` | `GracePeriodExpired` | — | `COMPLIANCE` | Start lock countdown |
| T9 | `COUNTDOWN_RUNNING` | `LocationProviderChanged(ON)` | — | `LOCATION_RESTORED` | — |
| T10 | `COUNTDOWN_RUNNING` | `LockNowRequested` | — | `LOCK_REQUIRED` | — |
| T11 | `COMPLIANCE` | `LockCountdownExpired` | — | `LOCK_REQUIRED` | — |
| T12 | `COMPLIANCE` | `ComplianceScreenDismissed` | `lockIteration > 0` | `LOCK_REQUIRED` | — |
| T13 | `COMPLIANCE` | `LocationProviderChanged(ON)` | — | `LOCATION_RESTORED` | — |
| T14 | `COMPLIANCE` | `LockNowRequested` | — | `LOCK_REQUIRED` | — |
| T15 | `LOCK_REQUIRED` | *(auto-advance)* | — | `LOCKED` | Execute lockNow(), finish Activity |
| T16 | `LOCKED` | `UserPresent` | — | `DEVICE_UNLOCKED` | — |
| T17 | `LOCKED` | `TimedLockExpired` | `timedLockDurationMs != null` | `LOCATION_RESTORED` | — |
| T18 | `DEVICE_UNLOCKED` | *(auto-advance)* | — | `VERIFYING` | Query location |
| T19 | `VERIFYING` | `LocationEnabled` | — | `LOCATION_RESTORED` | — |
| T20 | `VERIFYING` | `LocationStillDisabled` | — | `COMPLIANCE` | Increment lockIteration, start new countdown |
| T21 | `LOCATION_RESTORED` | *(auto-advance)* | — | `NORMAL` | Full cleanup |
| T22 | `LOCATION_RESTORED` | `LocationProviderChanged(OFF)` | `permissionGranted && enforcementArmed` | `LOCATION_DISABLED` | Restart cycle |

### 4.2 Recovery Transitions (on engine start / boot)

| # | Trigger | Persisted State | Condition | To State | Actions |
|---|---------|-----------------|-----------|----------|---------|
| R1 | `Engine.start()` | `NORMAL` | Location ON | `NORMAL` | Re-arm watchers |
| R2 | `Engine.start()` | `NORMAL` | Location OFF | `LOCATION_DISABLED` | Restart cycle |
| R3 | `Engine.start()` | `LOCATION_DISABLED` | Deadline passed | `COMPLIANCE` | Skip countdown |
| R4 | `Engine.start()` | `COUNTDOWN_RUNNING` | Deadline passed | `COMPLIANCE` | Skip countdown |
| R5 | `Engine.start()` | `COUNTDOWN_RUNNING` | Deadline future | `COUNTDOWN_RUNNING` | Resume countdown |
| R6 | `Engine.start()` | `COMPLIANCE` | Lock deadline passed | `LOCK_REQUIRED` | Execute lock |
| R7 | `Engine.start()` | `COMPLIANCE` | Lock deadline future | `COMPLIANCE` | Resume countdown |
| R8 | `Engine.start()` | `LOCK_REQUIRED` | — | `LOCK_REQUIRED` | Execute lock |
| R9 | `Engine.start()` | `LOCKED` | — | `LOCKED` | Re-arm UserPresent receiver |
| R10 | `Engine.start()` | `DEVICE_UNLOCKED` | — | `VERIFYING` | Query location |
| R11 | `Engine.start()` | `VERIFYING` | — | `VERIFYING` | Query location |
| R12 | `Engine.start()` | `LOCATION_RESTORED` | — | `LOCATION_RESTORED` | Run cleanup |
| R13 | `BootCompleted` | any | Location ON | `LOCATION_RESTORED` | Cleanup → NORMAL |
| R14 | `BootCompleted` | any | Location OFF + `enforcementArmed` | `LOCATION_DISABLED` | Restart cycle |

---

## 5. Entry Actions

Entry actions execute **after** the state transition is committed but **before** the next event is processed. They are idempotent — re-entering a state re-runs them safely.

### 5.1 Entry Action Table

| State | Entry Actions (in execution order) |
|----------------------|----------------------------------------------------------------------|
| `NORMAL` | 1. Cancel grace timer<br>2. Cancel lock countdown timer<br>3. Cancel timed-lock timer<br>4. Unregister `UserPresentReceiver`<br>5. Stop enforcement service (if no timed lock active)<br>6. `EmitLockoutStateChanged(false)`<br>7. `EmitServiceStateChanged("idle")`<br>8. Persist state |
| `LOCATION_DISABLED` | 1. Persist state + `countdownDeadline = now + gracePeriodMs`<br>2. Start grace timer (`policy.gracePeriodMs`)<br>3. Start enforcement service (action: `COMPLIANCE`)<br>4. `EmitServiceStateChanged("compliance")`<br>5. Dispatch auto-advance → `COUNTDOWN_RUNNING` |
| `COUNTDOWN_RUNNING` | 1. Start compliance Activity (`LocationComplianceActivity`)<br>2. Arm lock task mode<br>3. Persist state<br>4. `EmitLockoutStateChanged(false)` |
| `COMPLIANCE` | 1. Start lock countdown timer (`policy.lockCountdownMs`)<br>2. Persist state + `countdownDeadline = now + lockCountdownMs`<br>3. Update compliance Activity UI (show countdown)<br>4. `EmitLockoutStateChanged(false)` |
| `LOCK_REQUIRED` | 1. `ExecuteLockNow()` — `DevicePolicyManager.lockNow()`<br>2. Finish compliance Activity<br>3. Disarm lock task mode<br>4. `EmitLockResult("locked")`<br>5. `EmitLockoutStateChanged(true)`<br>6. Persist state<br>7. If `timedLockDurationMs != null`: start timed-lock timer<br>8. Dispatch auto-advance → `LOCKED` |
| `LOCKED` | 1. Arm `UserPresentReceiver` (`RECEIVER_EXPORTED`)<br>2. Persist state<br>3. Keep enforcement service running<br>4. `EmitServiceStateChanged("locked")` |
| `DEVICE_UNLOCKED` | 1. Persist state<br>2. `EmitLockoutStateChanged(false)`<br>3. Dispatch auto-advance → `VERIFYING` |
| `VERIFYING` | 1. Query location status via `LocationProbe`<br>2. If ON: dispatch `LocationEnabled`<br>3. If OFF: dispatch `LocationStillDisabled`<br>4. Persist state |
| `LOCATION_RESTORED` | 1. Dismiss compliance Activity<br>2. Disarm lock task mode<br>3. Cancel grace timer<br>4. Cancel lock countdown timer<br>5. Cancel timed-lock timer<br>6. Unregister `UserPresentReceiver`<br>7. Stop enforcement service (if no other reason)<br>8. `EmitLockResult("restored")`<br>9. `EmitLockoutStateChanged(false)`<br>10. `EmitServiceStateChanged("idle")`<br>11. Reset `lockIteration = 0`<br>12. Clear `timedLockDurationMs`<br>13. Persist state<br>14. Dispatch auto-advance → `NORMAL` |

### 5.2 Entry Action Idempotency Guarantees

| Action | Idempotency mechanism |
|------------------------|-----------------------------------------------------------|
| Start grace timer | Timer checks `isRunning()` before starting; no-op if already running |
| Start lock countdown | Same — checks `isRunning()` |
| Start compliance Activity | `Activity` launch intent has `FLAG_SINGLE_TOP`; no duplicate launch |
| Arm lock task | `LockTaskController.arm()` checks `isAllowlisted()` first; no-op if already armed |
| Execute lockNow() | **Not idempotent** — but `LOCK_REQUIRED` is transient, so it runs exactly once per cycle |
| Arm UserPresent receiver | `Context.registerReceiver()` is idempotent if already registered (checked via flag) |
| Persist state | `SharedPreferences.commit()` overwrites; safe to call repeatedly |

---

## 6. Exit Actions

Exit actions execute **before** the state transition is committed. They clean up resources that belong to the outgoing state.

### 6.1 Exit Action Table

| State | Exit Actions |
|----------------------|-----------------------------------------------------------|
| `NORMAL` | *(none — resting state has no resources to clean up)* |
| `LOCATION_DISABLED` | *(none — grace timer continues into COUNTDOWN_RUNNING)* |
| `COUNTDOWN_RUNNING` | 1. Cancel grace timer (if leaving to COMPLIANCE or LOCATION_RESTORED)<br>2. *(compliance Activity stays if going to COMPLIANCE; dismissed if going to LOCATION_RESTORED)* |
| `COMPLIANCE` | 1. Cancel lock countdown timer |
| `LOCK_REQUIRED` | *(none — transient, entry already performed the lock)* |
| `LOCKED` | 1. Unregister `UserPresentReceiver` |
| `DEVICE_UNLOCKED` | *(none — transient)* |
| `VERIFYING` | *(none — transient)* |
| `LOCATION_RESTORED` | *(none — cleanup happens in entry actions)* |

### 6.2 Entry vs. Exit Action Division

The design deliberately puts **most cleanup in entry actions** of the *destination* state (especially `LOCATION_RESTORED` and `NORMAL`), not in exit actions of the *source* state. This is because:

1. **Crash safety** — if the process dies mid-transition, exit actions may not run. Entry actions of the destination state run on recovery.
2. **Idempotency** — entry actions are designed to be re-runnable. Exit actions are not.
3. **Simplicity** — one place to look for "what does state X set up" and "what does state X tear down."

The only exit actions are **timer cancellations** (which are safe to skip — the timer fires harmlessly into a state that ignores the event) and **receiver unregistration** (which is also safe to skip — the receiver just won't fire).

---

## 7. Guard Conditions

Guards are boolean predicates evaluated by `PolicyManager` before allowing a transition. If a guard fails, the event is **ignored** (logged at WARN level).

### 7.1 Guard Table

| Guard | Expression | Applies to transitions |
|----------------|-----------------------------------------------------------|-------------------------|
| `G1` | `state.permissionGranted == true` | T1, T2, T3, T4, R2, R14 |
| `G2` | `state.enforcementArmed == true` | T1, T4, R2, R14 |
| `G3` | `state.uninstallBlocked == true` | T1 (location enforcement only matters when uninstall is blocked) |
| `G4` | `state.lockIteration > 0` | T12 (dismissal only escalates to lock on 2nd+ cycle) |
| `G5` | `state.timedLockDurationMs != null` | T17 (timed lock expiry only if a duration was set) |
| `G6` | `locationProbe.isLocationEnabled() == true` | T19, R13 |
| `G7` | `locationProbe.isLocationEnabled() == false` | T20, R2, R14 |

### 7.2 Guard Evaluation Order

```
PolicyManager.evaluate(state, event):
  1. Check if event is applicable to current state (transition exists in table)
  2. If yes, evaluate guard(s)
  3. If guard passes → return [TransitionTo(target), ...actions]
  4. If guard fails → return [] (log: "Guard Gx failed for event Y in state Z")
  5. If no transition exists → return [] (log: "No transition for event Y in state Z")
```

---

## 8. Timeout Handling

### 8.1 Timer Catalog

| Timer | Started in state | Duration | Fires event | Transitions to | Cancelled by |
|----------------|-------------------|---------------------------|------------------------------|----------------------|--------------------------|
| **Grace Timer** | `LOCATION_DISABLED` (entry) | `policy.gracePeriodMs` (120s) | `GracePeriodExpired` | `COMPLIANCE` | Exit `COUNTDOWN_RUNNING` → `LOCATION_RESTORED` |
| **Lock Countdown** | `COMPLIANCE` (entry) | `policy.lockCountdownMs` (120s) | `LockCountdownExpired` | `LOCK_REQUIRED` | Exit `COMPLIANCE` → `LOCATION_RESTORED` |
| **Timed Lock** | `LOCK_REQUIRED` (entry, if duration set) | `timedLockDurationMs` (user-specified or 60s) | `TimedLockExpired` | `LOCATION_RESTORED` | `UserPresent` (if manual unlock allowed) |
| **Service Self-Stop** | `NORMAL` (entry, if service running) | `policy.lockDelayMs` (30s) | `ServiceSelfStop` | (stays `NORMAL`, stops service) | Any transition out of `NORMAL` |

### 8.2 Timer Lifecycle Diagram

```
LOCATION_DISABLED ──start grace──▶ [Grace Timer running]
                                        │
                                        │ GracePeriodExpired
                                        ▼
COUNTDOWN_RUNNING ──cancel grace──▶ [Grace Timer cancelled]
                      ──start lock──▶ [Lock Countdown running]
                                        │
                                        │ LockCountdownExpired
                                        ▼
COMPLIANCE ──cancel lock──▶ [Lock Countdown cancelled]
              ──(if duration)──▶ [Timed Lock running]
                                        │
                                        │ TimedLockExpired
                                        ▼
LOCK_REQUIRED ──▶ LOCKED ──▶ DEVICE_UNLOCKED ──▶ VERIFYING
                     │                              │
                     │ TimedLockExpired             │ LocationStillDisabled
                     │ (if timed lock)              │
                     ▼                              ▼
              LOCATION_RESTORED              COMPLIANCE (new countdown)
```

### 8.3 Timer Cancellation Semantics

The `CountdownTimer` (see [`LockCountdownTimer`](android/app/src/main/kotlin/com/example/app_lock/service/LockCountdownTimer.kt:24)) stores a **direct `Runnable` reference** and calls `handler.removeCallbacks(runnable)` — NOT `removeCallbacksAndMessages(null)`. This is critical because:

- `removeCallbacksAndMessages(null)` cancels **all** pending callbacks on the handler, including unrelated UI updates.
- Surgical `removeCallbacks(runnable)` cancels only the specific timer.

In the new design, `TimerScheduler` (the interface) exposes:
```
startTimer(timerId: String, durationMs: Long, event: EnforcementEvent)
cancelTimer(timerId: String)
cancelAllTimers()  // used only in LOCATION_RESTORED entry
```

### 8.4 Deadline Persistence (Cross-Reboot Timers)

Timers use `elapsedRealtime` for in-process operation but persist **wall-clock deadlines** (`System.currentTimeMillis() + duration`) for cross-reboot recovery:

```
Timer start:
  1. handler.postDelayed(runnable, durationMs)           // in-process
  2. stateStore.saveDeadline(timerId, now + durationMs)  // cross-reboot

Timer fire (in-process):
  1. runnable runs → dispatch(GracePeriodExpired)
  2. stateStore.clearDeadline(timerId)

Recovery (on engine start):
  1. deadline = stateStore.getDeadline(timerId)
  2. if deadline == null → timer was not running, skip
  3. remaining = deadline - System.currentTimeMillis()
  4. if remaining <= 0 → dispatch expired event immediately
  5. if remaining > 0 → handler.postDelayed(runnable, remaining)
```

---

## 9. Persistence

### 9.1 Persisted State Schema

```
EnforcementStateStore (SharedPreferences — single file: "enforcement_state")

┌─────────────────────────┬──────────────┬───────────────────────────────────────┐
│ Key                     │ Type         │ Description                           │
├─────────────────────────┼──────────────┼───────────────────────────────────────┤
│ currentState            │ String       │ Enum name of current state            │
│ countdownDeadline       │ Long?        │ Wall-clock ms when grace expires      │
│ lockCountdownDeadline   │ Long?        │ Wall-clock ms when lock countdown     │
│                         │              │ expires                               │
│ timedLockDeadline       │ Long?        │ Wall-clock ms when timed lock expires │
│ timedLockDurationMs     │ Long?        │ Original duration (for restoration)   │
│ lockIteration           │ Int          │ How many lock cycles have occurred    │
│ enforcementArmed        │ Boolean      │ Is enforcement active?                │
│ permissionGranted       │ Boolean      │ Is device admin permission granted?   │
│ uninstallBlocked        │ Boolean      │ Is uninstall block active?            │
│ lockoutActive           │ Boolean      │ Is device currently locked?           │
│ serviceRunning          │ Boolean      │ Is foreground service running?        │
│ serviceAction           │ String?      │ NONE | COUNTDOWN | TIMED_LOCK |       │
│                         │              │ COMPLIANCE                            │
│ version                 │ Int          │ Schema version (for migrations)       │
└─────────────────────────┴──────────────┴───────────────────────────────────────┘
```

### 9.2 When to Persist

| Trigger | What to persist |
|---------------------------|---------------------------------------------------|
| State transition (every) | `currentState` + any changed fields |
| Timer started | `countdownDeadline` / `lockCountdownDeadline` / `timedLockDeadline` |
| Timer cancelled | Clear corresponding deadline key |
| `enforcementArmed` changed | `enforcementArmed` |
| Permission changed | `permissionGranted` |
| Uninstall block changed | `uninstallBlocked` |
| `lockIteration` changed | `lockIteration` |
| Engine shutdown | Full state snapshot |

### 9.3 Write Semantics

- **Use `commit()` (synchronous), not `apply()` (async)** — crash safety requires the write to complete before the engine proceeds.
- Writes happen on the **engine thread** (single-threaded), so there is no contention.
- The `StatePersistence` interface (defined by engine, implemented by `EnforcementStateStore`) exposes:
  ```
  save(state: EnforcementStateSnapshot)
  load(): EnforcementStateSnapshot?
  clear()
  ```

### 9.4 Schema Versioning

```
version 1: Initial schema (this design)
version 2+: Migration function reads old keys, writes new format
```

On load, if `version` is missing or lower than current, the migration function runs before state reconciliation.

---

## 10. Crash Recovery

### 10.1 Crash Scenario Classification

| Scenario | What happens | Recovery trigger |
|---------------------------|---------------------------------------------------|------------------|
| **Process death** (OS killed) | In-memory state lost; persisted state intact | `Service.onStartCommand(null)` → `ServiceRestarted` event |
| **Service restart** (START_STICKY) | New process; `intent == null` | `ServiceRestarted` event |
| **Engine crash** (uncaught exception) | Engine thread dies; process may survive | `Service.onStartCommand` re-creates engine |
| **ANR recovery** | System killed ANR-ing process | Same as process death |

### 10.2 Crash Recovery Procedure

```
On ServiceRestarted event (intent == null):

  Step 1: Reconstruct engine
    └─ EventDispatcher.start()
       └─ StateManager.restore()
          ├─ Read persisted state from StateStore
          ├─ Read all deadlines (countdown, lock, timedLock)
          └─ Build in-memory EnforcementState

  Step 2: Reconcile persisted state with reality
    └─ PolicyManager.evaluate(state, ServiceRestarted)
       ├─ Check location status (LocationProbe)
       ├─ Check deadline expiry (now vs. persisted deadlines)
       └─ Return recovery actions

  Step 3: Execute recovery actions
    └─ ActionExecutor.execute(actions)
       ├─ Transition to reconciled state
       ├─ Re-arm receivers (UserPresent, LocationProviderChange)
       ├─ Re-start timers (with remaining duration)
       ├─ Re-schedule WorkManager
       └─ Re-start foreground service (crash-safe via ServiceGuard)

  Step 4: Resume normal operation
    └─ EventDispatcher is now accepting events
```

### 10.3 Crash Recovery State Reconciliation

```
reconcileOnCrash(persistedState, currentTime, locationEnabled):

  switch (persistedState.currentState):

    case NORMAL:
      if (!locationEnabled && enforcementArmed):
        → LOCATION_DISABLED  (restart cycle)
      else:
        → NORMAL  (re-arm watchers only)

    case LOCATION_DISABLED:
      // Transient — should have auto-advanced. Treat as COUNTDOWN_RUNNING.
      if (countdownDeadline <= currentTime):
        → COMPLIANCE  (grace expired while dead)
      else:
        → COUNTDOWN_RUNNING  (resume grace with remaining time)

    case COUNTDOWN_RUNNING:
      if (countdownDeadline <= currentTime):
        → COMPLIANCE  (grace expired while dead)
      else:
        → COUNTDOWN_RUNNING  (resume grace with remaining time)

    case COMPLIANCE:
      if (lockCountdownDeadline <= currentTime):
        → LOCK_REQUIRED  (lock countdown expired while dead → execute lock)
      else:
        → COMPLIANCE  (resume lock countdown with remaining time)

    case LOCK_REQUIRED:
      // Transient — lock may or may not have executed.
      // Safe approach: re-execute lockNow() (idempotent from user perspective).
      → LOCK_REQUIRED  (re-execute lock)

    case LOCKED:
      // Device may have been unlocked while process was dead.
      // Re-arm UserPresent receiver. If already unlocked, receiver fires immediately.
      → LOCKED  (re-arm receiver)

    case DEVICE_UNLOCKED:
      // Transient — treat as VERIFYING.
      → VERIFYING  (query location)

    case VERIFYING:
      → VERIFYING  (re-query location)

    case LOCATION_RESTORED:
      // Transient — run cleanup, advance to NORMAL.
      → LOCATION_RESTORED  (run cleanup → NORMAL)
```

### 10.4 Crash Recovery: Timer Restoration

```
restoreTimers(persistedState, currentTime):

  // Grace timer
  if (persistedState.countdownDeadline != null):
    remaining = persistedState.countdownDeadline - currentTime
    if (remaining > 0):
      timerScheduler.startTimer("grace", remaining, GracePeriodExpired)
    else:
      eventDispatcher.dispatch(GracePeriodExpired)  // expired while dead

  // Lock countdown timer
  if (persistedState.lockCountdownDeadline != null):
    remaining = persistedState.lockCountdownDeadline - currentTime
    if (remaining > 0):
      timerScheduler.startTimer("lockCountdown", remaining, LockCountdownExpired)
    else:
      eventDispatcher.dispatch(LockCountdownExpired)

  // Timed lock timer
  if (persistedState.timedLockDeadline != null):
    remaining = persistedState.timedLockDeadline - currentTime
    if (remaining > 0):
      timerScheduler.startTimer("timedLock", remaining, TimedLockExpired)
    else:
      eventDispatcher.dispatch(TimedLockExpired)
```

---

## 11. Reboot Recovery

### 11.1 Reboot vs. Crash Recovery

| Aspect | Crash Recovery | Reboot Recovery |
|----------------|----------------|-----------------|
| Trigger | `ServiceRestarted` (null intent) | `BootCompleted` |
| Persisted state | Intact | Intact |
| In-memory state | Lost | Lost |
| Receivers | Need re-registration | Need re-registration |
| WorkManager | Survives (persisted) | Survives (persisted) |
| Foreground service | Need restart | Need restart |
| `elapsedRealtime` | Reset (process death) | **Reset to 0** |
| Wall-clock time | Continues | Continues (NTP-corrected) |
| Location status | Unknown (must probe) | Unknown (must probe) |

**Key difference:** `elapsedRealtime` resets to 0 on reboot. This is why all deadlines are persisted as **wall-clock ms** (`System.currentTimeMillis()`), not `elapsedRealtime`. The existing [`LocationComplianceStateStore`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceStateStore.kt:68) already uses wall-clock for `countdownDeadline`.

### 11.2 Reboot Recovery Procedure

```
On BootCompleted event:

  Step 1: Start enforcement service
    └─ LifecycleManager.startService(EnforcementForegroundService)
       └─ ServiceGuard.startForegroundCrashSafe(this)  // handles null-intent restart

  Step 2: Restore state
    └─ StateManager.restore()
       ├─ Read persisted state
       ├─ Read deadlines (wall-clock)
       └─ Build in-memory EnforcementState

  Step 3: Reconcile state
    └─ reconcileOnReboot(persistedState, currentTime, locationEnabled)

  Step 4: Re-arm system watchers
    ├─ Register LocationProviderChangeReceiver
    ├─ Register UserPresentReceiver (if state == LOCKED)
    └─ SchedulePeriodicReapply (WorkManager)

  Step 5: Restore timers
    └─ restoreTimers(persistedState, currentTime)

  Step 6: Resume normal operation
    └─ EventDispatcher accepting events
```

### 11.3 Reboot Reconciliation Logic

```
reconcileOnReboot(persistedState, currentTime, locationEnabled):

  // First: check if enforcement should be active at all
  if (!persistedState.enforcementArmed || !persistedState.permissionGranted):
    → NORMAL  (enforcement not active, just re-arm watchers)

  // Second: check location status — this is the ground truth
  if (locationEnabled):
    // Location is ON — regardless of persisted state, go to restored
    → LOCATION_RESTORED  (cleanup → NORMAL)

  // Third: location is OFF — resume enforcement from persisted state
  switch (persistedState.currentState):

    case NORMAL:
      // Was normal before reboot, but location is now off.
      // Start fresh enforcement cycle.
      → LOCATION_DISABLED

    case LOCATION_DISABLED:
    case COUNTDOWN_RUNNING:
      if (countdownDeadline <= currentTime):
        → COMPLIANCE  (grace expired during reboot)
      else:
        → COUNTDOWN_RUNNING  (resume grace)

    case COMPLIANCE:
      if (lockCountdownDeadline <= currentTime):
        → LOCK_REQUIRED  (lock countdown expired during reboot)
      else:
        → COMPLIANCE  (resume lock countdown)

    case LOCK_REQUIRED:
    case LOCKED:
      // Device was locked before reboot. After reboot, device is unlocked
      // (reboot unlocks the device). Re-lock immediately.
      → LOCK_REQUIRED  (re-execute lockNow())

    case DEVICE_UNLOCKED:
    case VERIFYING:
      // Was verifying location before reboot. Location is OFF (checked above).
      → COMPLIANCE  (start new countdown)

    case LOCATION_RESTORED:
      // Was restoring. Location is OFF now. Start fresh cycle.
      → LOCATION_DISABLED
```

### 11.4 Reboot Recovery: Special Cases

| Special case | Handling |
|---------------------------|---------------------------------------------------|
| **Device was `LOCKED` before reboot** | Reboot unlocks the device. `LOCK_REQUIRED` is entered, `lockNow()` re-executes. User sees lock screen again. |
| **Timed lock was active** | `timedLockDeadline` is checked. If expired → `LOCATION_RESTORED`. If future → resume timer. |
| **`lockIteration` was > 0** | Preserved. Next `COMPLIANCE` cycle uses the incremented iteration. |
| **Permission revoked during reboot** | `permissionGranted` is re-checked via `DevicePolicyManager.isAdminActive()`. If false → `NORMAL` (disarm). |
| **Uninstall block removed during reboot** | `uninstallBlocked` is re-checked. If false → enforcement may be disarmed (depends on policy). |
| **Clock skew (NTP correction)** | Wall-clock deadlines may jump. If deadline is now in the past, the timer fires immediately (safe — event is processed normally). |

---

## 12. State Invariants

Invariants are properties that **must always hold true** in each state. They are checked by `StateManager.assertInvariants()` after every transition (debug builds only — throws `IllegalStateException` on violation).

### 12.1 Per-State Invariants

| State | Invariants |
|----------------------|-----------------------------------------------------------|
| `NORMAL` | Grace timer NOT running · Lock countdown NOT running · `UserPresentReceiver` NOT registered · Compliance Activity NOT showing · `lockoutActive == false` |
| `LOCATION_DISABLED` | Grace timer running · `countdownDeadline != null` · Enforcement service running |
| `COUNTDOWN_RUNNING` | Grace timer running · `countdownDeadline != null` · Compliance Activity showing · Lock task armed |
| `COMPLIANCE` | Lock countdown running · `lockCountdownDeadline != null` · Compliance Activity showing · Grace timer NOT running |
| `LOCK_REQUIRED` | `lockoutActive == true` (set by entry action) · Compliance Activity finishing |
| `LOCKED` | `UserPresentReceiver` registered · `lockoutActive == true` · Enforcement service running |
| `DEVICE_UNLOCKED` | `lockoutActive == false` · `UserPresentReceiver` still registered (will be unregistered in `LOCATION_RESTORED`) |
| `VERIFYING` | Location probe in progress · `lockoutActive == false` |
| `LOCATION_RESTORED` | All timers cancelled · `UserPresentReceiver` NOT registered · Compliance Activity dismissed · `lockoutActive == false` · `lockIteration == 0` |

### 12.2 Global Invariants (all states)

| # | Invariant |
|----|-----------------------------------------------------------|
| I1 | Only one state is active at any time |
| I2 | All state transitions happen on the engine thread |
| I3 | Persisted state matches in-memory state after every transition |
| I4 | No timer is running without a corresponding persisted deadline |
| I5 | No persisted deadline exists without a corresponding timer (after recovery) |
| I6 | `lockoutActive` is true iff state is `LOCK_REQUIRED` or `LOCKED` |
| I7 | `UserPresentReceiver` is registered iff state is `LOCKED` or `DEVICE_UNLOCKED` |
| I8 | Compliance Activity is showing iff state is `COUNTDOWN_RUNNING` or `COMPLIANCE` |
| I9 | `enforcementArmed` implies `permissionGranted` (can't arm without permission) |
| I10 | `lockIteration` is reset to 0 on entry to `LOCATION_RESTORED` |

---

## 13. Recovery Decision Tree

```
Engine.start() or BootCompleted
         │
         ▼
   Read persisted state
         │
         ▼
   Is enforcementArmed && permissionGranted?
         │
    ┌────┴────┐
    │ NO      │ YES
    ▼         ▼
  NORMAL    Is location enabled?
  (disarm)       │
           ┌─────┴─────┐
           │ YES       │ NO
           ▼           ▼
     LOCATION_    What was persisted state?
     RESTORED          │
     → NORMAL    ┌─────┴────────────────────────────────────┐
                  │                                          │
          NORMAL / LOCATION_RESTORED              LOCATION_DISABLED /
                  │                                COUNTDOWN_RUNNING
                  ▼                                          │
           LOCATION_DISABLED                          Check deadline
                                                  ┌─────┴─────┐
                                                  │ expired   │ future
                                                  ▼           ▼
                                              COMPLIANCE   Resume timer
                                                  │
                                                  ▼
                                          Check lock deadline
                                          ┌─────┴─────┐
                                          │ expired   │ future
                                          ▼           ▼
                                      LOCK_REQUIRED  Resume timer
                                      (execute lock)
                                          │
                  ┌───────────────────────┘
                  ▼
            COMPLIANCE / LOCK_REQUIRED / LOCKED / DEVICE_UNLOCKED / VERIFYING
                  │
                  ▼
            Apply reconciliation (see §10.3 / §11.3)
                  │
                  ▼
            Re-arm receivers
            Restore timers
            Re-schedule WorkManager
            Start foreground service
                  │
                  ▼
            Engine ready — accepting events
```

---

## 14. Event-to-Transition Cross-Reference

This table maps every `EnforcementEvent` (from the [Event Catalog](plans/enforcement-engine-design.md:1491)) to the transitions it triggers:

| Event | Applicable states | Transition | Guard |
|--------------------------|---------------------------------|------------|-------|
| `LocationProviderChanged(OFF)` | `NORMAL` | T1 → `LOCATION_DISABLED` | G1, G2, G3 |
| `LocationProviderChanged(OFF)` | `LOCATION_RESTORED` | T22 → `LOCATION_DISABLED` | G1, G2, G3 |
| `LocationProviderChanged(ON)` | `LOCATION_DISABLED` | T7 → `LOCATION_RESTORED` | — |
| `LocationProviderChanged(ON)` | `COUNTDOWN_RUNNING` | T9 → `LOCATION_RESTORED` | — |
| `LocationProviderChanged(ON)` | `COMPLIANCE` | T13 → `LOCATION_RESTORED` | — |
| `GracePeriodExpired` | `COUNTDOWN_RUNNING` | T8 → `COMPLIANCE` | — |
| `LockCountdownExpired` | `COMPLIANCE` | T11 → `LOCK_REQUIRED` | — |
| `ComplianceScreenDismissed` | `COMPLIANCE` | T12 → `LOCK_REQUIRED` | G4 |
| `UserPresent` | `LOCKED` | T16 → `DEVICE_UNLOCKED` | — |
| `TimedLockExpired` | `LOCKED` | T17 → `LOCATION_RESTORED` | G5 |
| `LocationEnabled` | `VERIFYING` | T19 → `LOCATION_RESTORED` | G6 |
| `LocationStillDisabled` | `VERIFYING` | T20 → `COMPLIANCE` | G7 |
| `LockNowRequested` | `NORMAL`, `COUNTDOWN_RUNNING`, `COMPLIANCE` | T2/T10/T14 → `LOCK_REQUIRED` | G1 |
| `LockForDurationRequested(d)` | `NORMAL` | T3 → `LOCK_REQUIRED` | G1 |
| `BootCompleted` | any | R13/R14 | G1, G2 |
| `ServiceRestarted` | any | R1–R12 | — |
| `PermissionRevoked` | any | T5 → `NORMAL` | — |
| `EnforcementDisarmed` | any | → `NORMAL` | — |
| *(auto-advance)* | `LOCATION_DISABLED` | T6 → `COUNTDOWN_RUNNING` | — |
| *(auto-advance)* | `LOCK_REQUIRED` | T15 → `LOCKED` | — |
| *(auto-advance)* | `DEVICE_UNLOCKED` | T17 → `VERIFYING` | — |
| *(auto-advance)* | `LOCATION_RESTORED` | T21 → `NORMAL` | — |

---

## Appendix A: State × Action Matrix

This matrix shows which entry actions fire for each state:

| Action | NORMAL | LOCATION_DISABLED | COUNTDOWN_RUNNING | COMPLIANCE | LOCK_REQUIRED | LOCKED | DEVICE_UNLOCKED | VERIFYING | LOCATION_RESTORED |
|--------------------------|--------|-------------------|-------------------|------------|----------------|--------|-----------------|-----------|-------------------|
| Cancel grace timer | ✓ | | | | | | | | ✓ |
| Cancel lock countdown | ✓ | | | | | | | | ✓ |
| Cancel timed-lock timer | ✓ | | | | | | | | ✓ |
| Start grace timer | | ✓ | | | | | | | |
| Start lock countdown | | | | ✓ | | | | | |
| Start timed-lock timer | | | | | ✓ (if duration) | | | | |
| Start compliance Activity | | | ✓ | | | | | | |
| Finish compliance Activity | | | | | ✓ | | | | ✓ |
| Arm lock task | | | ✓ | | | | | | |
| Disarm lock task | | | | | ✓ | | | | ✓ |
| Execute lockNow() | | | | | ✓ | | | | |
| Arm UserPresentReceiver | | | | | | ✓ | | | |
| Unregister UserPresentReceiver | ✓ | | | | | | | | ✓ |
| Start enforcement service | | ✓ | | | | | | | |
| Stop enforcement service | ✓ | | | | | | | | ✓ |
| Query location | | | | | | | | ✓ | |
| EmitLockoutStateChanged | ✓ (false) | | | | ✓ (true) | | ✓ (false) | | ✓ (false) |
| EmitLockResult | | | | | ✓ ("locked") | | | | ✓ ("restored") |
| EmitServiceStateChanged | ✓ ("idle") | ✓ ("compliance") | | | | ✓ ("locked") | | | ✓ ("idle") |
| Persist state | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Reset lockIteration | | | | | | | | | ✓ |
| Clear timedLockDurationMs | | | | | | | | | ✓ |
| Auto-advance | | ✓ → COUNTDOWN_RUNNING | | | ✓ → LOCKED | | ✓ → VERIFYING | | ✓ → NORMAL |

---

## Appendix B: Mapping to EnforcementEngine Design

This state machine is the **policy** that `PolicyManager` (from [`plans/enforcement-engine-design.md`](plans/enforcement-engine-design.md:741)) implements. The mapping is:

| State machine concept | EnforcementEngine component |
|---------------------------|---------------------------------------------------|
| State definitions | `EnforcementState.complianceState` field |
| Transition table | `PolicyManager.evaluate(state, event)` — the `when` block |
| Entry actions | `ActionExecutor` executes `EnforcementAction` list returned by `PolicyManager` |
| Exit actions | Part of `PolicyManager` return value (cleanup actions before transition) |
| Guard conditions | Boolean checks in `PolicyManager.evaluate()` |
| Timers | `TimerScheduler` interface → `CountdownTimer` implementation |
| Persistence | `StatePersistence` interface → `EnforcementStateStore` implementation |
| Crash recovery | `StateManager.restore()` + `PolicyManager.evaluate(state, ServiceRestarted)` |
| Reboot recovery | `PolicyManager.evaluate(state, BootCompleted)` |
| Invariants | `StateManager.assertInvariants()` (debug builds) |

The `PolicyManager` pseudo-code from the parent design doc (§4.4) is expanded by this state machine into a complete, deterministic transition table. Every `(state, event)` pair has a defined outcome — either a transition with actions, or an explicit "no transition" (ignored event).

---

## Appendix C: Problems Solved

This state machine design directly addresses problems identified in [`plans/architecture-analysis.md`](plans/architecture-analysis.md:1):

| Problem | How this state machine solves it |
|----------------|-----------------------------------------------------------|
| **P-02** (RECEIVER_EXPORTED inconsistency) | `LOCKED` entry action explicitly specifies `RECEIVER_EXPORTED` |
| **P-05** (God Object — 635-line coordinator) | State logic moves to `PolicyManager` (pure function table) |
| **P-07** (State scattered across 3 stores) | Single `EnforcementStateStore` with unified schema (§9.1) |
| **P-08** (Wall-clock vs elapsedRealtime confusion) | All deadlines are wall-clock ms (§8.4, §11.1) |
| **P-09** (No crash recovery for mid-countdown death) | §10.3 reconciliation + §10.4 timer restoration |
| **P-10** (No reboot recovery for LOCKED state) | §11.3 handles `LOCKED` → `LOCK_REQUIRED` re-lock |
| **P-13** (Thread-safety — 20+ @Synchronized) | All transitions on engine thread (§1, invariant I2) |
| **P-15** (Timer cancellation nukes all callbacks) | §8.3 — surgical `removeCallbacks(runnable)` |
| **P-16** (No state invariants) | §12 — 10 global invariants + per-state invariants |
| **P-18** (lockIteration not persisted across reboot) | §9.1 — `lockIteration` in persisted schema |
| **P-19** (No schema versioning) | §9.4 — `version` field + migration function |
