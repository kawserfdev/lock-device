# 🏗️ EnforcementEngine — Refactor Design

> **Scope:** Architecture design for refactoring the existing project around a single `EnforcementEngine`.
> **Constraint:** No code. Design only — package structure, class diagram, responsibilities, sequence diagrams.
> **Date:** 2026-07-07

---

## Table of Contents

1. [Design Principles](#1-design-principles)
2. [Package Structure](#2-package-structure)
3. [Class Diagram](#3-class-diagram)
4. [Responsibilities](#4-responsibilities)
5. [Sequence Diagrams](#5-sequence-diagrams)
6. [Event & Action Catalog](#6-event--action-catalog)
7. [Threading Model](#7-threading-model)
8. [Mapping: Old → New](#8-mapping-old--new)

---

## 1. Design Principles

### 1.1 The Golden Rule

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│   All business decisions happen inside EnforcementEngine.       │
│                                                                 │
│   Receivers  →  only send events                                │
│   Services   →  only keep the process alive                     │
│   Controllers →  only execute commands                          │
│   Activity   →  only displays state and sends UI events         │
│   Channels   →  only transport requests and notifications       │
│                                                                 │
│   EnforcementEngine decides everything.                         │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 1.2 Decision Boundary Rules

| Component       | CAN do                                    | CANNOT do                                      |
|-----------------|-------------------------------------------|------------------------------------------------|
| Receiver        | Detect system event, create Event, dispatch | Read/write state, call controllers, start services, evaluate conditions |
| Service         | Call `startForeground()`, emit lifecycle event | Decide what action to perform, call controllers, read state |
| Controller      | Execute a single command, return result    | Decide WHEN to execute, read/write engine state, emit events |
| Activity        | Display state, emit UI events (dismissed, button pressed) | Construct controllers, start services, transition state |
| Channel Handler | Create Event from Flutter request, dispatch | Execute commands, read/write state, call controllers |
| EnforcementEngine | Evaluate state + event → decide actions, execute actions, transition state, emit notifications | Direct system I/O (delegates to controllers) |

### 1.3 Event-Driven Architecture

The system follows a **unidirectional event flow**:

```
Event Source → EventDispatcher → EnforcementEngine → Action Executor → Side Effect
                                      ↓
                              StateManager (persist)
                                      ↓
                              Notification (Flutter)
```

No component ever calls back into a component "above" it in this flow. The engine is the single hub through which all events flow and all actions are dispatched.

---

## 2. Package Structure

### 2.1 New Package Tree

```
com.example.app_lock/
│
├── engine/                              ═══ THE BRAIN ═══
│   │
│   ├── EnforcementEngine.kt             # Public facade — single entry point
│   ├── EventDispatcher.kt               # Receives events from all sources, serializes
│   ├── StateManager.kt                  # Pure state machine + persistence
│   ├── PolicyManager.kt                 # Decides actions from (state, event) pairs
│   ├── LifecycleManager.kt              # Manages services, activities, timers, receivers
│   ├── ActionExecutor.kt                # Routes actions to controllers/lifecycle/notifier
│   │
│   └── model/
│       ├── EnforcementEvent.kt          # Sealed class — all inbound events
│       ├── EnforcementAction.kt         # Sealed class — all outbound actions
│       ├── EnforcementState.kt          # Unified state representation
│       ├── EnforcementPolicy.kt         # Configuration constants (grace, lockout, etc.)
│       └── EnforcementResult.kt         # Result of action execution
│
├── events/                              ═══ EVENT SOURCES (Receivers) ═══
│   │
│   ├── LocationProviderChangeReceiver.kt   # PROVIDERS_CHANGED → event
│   ├── UserPresentReceiver.kt              # USER_PRESENT → event (CONSOLIDATED)
│   ├── BootCompletedReceiver.kt            # BOOT_COMPLETED → event
│   └── DeviceAdminEventReceiver.kt         # DeviceAdminReceiver callbacks → events
│
├── service/                             ═══ PROCESS KEEP-ALIVE ═══
│   │
│   ├── EnforcementForegroundService.kt     # CONSOLIDATED single foreground service
│   └── ServiceGuard.kt                     # startForeground safety helper
│
├── controller/                          ═══ COMMAND EXECUTORS ═══
│   │
│   ├── DeviceAdminController.kt            # DPM: lockNow, lockForDuration, restrictions
│   ├── LocationController.kt               # Location: forceLocationOn, isLocationEnabled
│   ├── LocationWatchController.kt          # Realtime: startWatch, stopWatch (receiver mgmt)
│   ├── UninstallController.kt              # Uninstall: setBlocked, isBlocked (NO side-effects)
│   ├── LockTaskController.kt               # Lock Task: arm, disarm
│   ├── WorkScheduler.kt                    # WorkManager: scheduleReapply, cancelReapply
│   └── ProvisioningController.kt           # Provisioning: launchProvisioningIntent
│
├── ui/                                  ═══ COMPLIANCE SCREEN ═══
│   │
│   └── LocationComplianceActivity.kt       # Displays state, emits UI events
│
├── channel/                             ═══ FLUTTER BRIDGE ═══
│   │
│   ├── LockMethodChannel.kt               # Dispatches to engine via events
│   ├── LockEventChannel.kt                # Emits engine notifications to Flutter
│   ├── EventEmitter.kt                    # Thread-safe emission interface
│   └── handler/
│       ├── AppLockHandler.kt              # Creates lock/service events
│       ├── PermissionHandler.kt           # Creates permission events
│       └── UninstallHandler.kt            # Creates uninstall events
│
├── persistence/                         ═══ STATE STORAGE ═══
│   │
│   ├── ComplianceStateStore.kt            # Compliance state (SharedPreferences)
│   ├── EnforcementStateStore.kt           # Lockout/service/permission state
│   └── StateStoreProvider.kt              # Factory for store access from any component
│
├── timer/                               ═══ TIMERS ═══
│   │
│   └── CountdownTimer.kt                  # Pure timer, callback on expiry
│
├── permission/                          ═══ PERMISSION UTILITIES ═══
│   │
│   └── ActivityLauncher.kt                # Fun interface for Activity intent launching
│
├── worker/                              ═══ WORKMANAGER ═══
│   │
│   └── ReapplyWorker.kt                   # Emits PeriodicReapplyTriggered event
│
├── util/
│   └── Constants.kt                       # Channel names, method names, event types
│
└── MainActivity.kt                        # Composition root — wires everything
```

### 2.2 Package Dependency Rules

```
engine/        ← depends on: controller/, persistence/, timer/, channel/, ui/, service/
                   (via interfaces only — never concrete implementations of receivers/services)

events/        ← depends on: engine/ (EventDispatcher only — via static access)
                   NOTHING ELSE — no controllers, no state, no services

service/       ← depends on: engine/ (EventDispatcher only — via static access)
                   NOTHING ELSE — no controllers, no state, no coordinators

controller/    ← depends on: Android framework APIs only (DPM, LocationManager, etc.)
                   NO engine, NO events, NO state — pure command execution

ui/            ← depends on: engine/ (EventDispatcher for events, engine for state queries)
                   NO controllers, NO services

channel/       ← depends on: engine/ (EventDispatcher for requests, EventEmitter for notifications)
                   NO controllers, NO state

worker/        ← depends on: engine/ (EventDispatcher only)
                   NOTHING ELSE

persistence/   ← depends on: Android SharedPreferences only
                   NO engine, NO controllers

timer/         ← depends on: Android Handler only
                   NO engine, NO controllers
```

### 2.3 Dependency Inversion

The `engine/` package defines interfaces that `controller/`, `persistence/`, `timer/`, `channel/`, and `service/` packages implement. The engine never references concrete classes in these packages — it references interfaces.

```
engine/ defines:
  ├── interface DeviceAdminOperations     ← implemented by DeviceAdminController
  ├── interface LocationOperations        ← implemented by LocationController
  ├── interface LocationWatchOperations   ← implemented by LocationWatchController
  ├── interface UninstallOperations       ← implemented by UninstallController
  ├── interface LockTaskOperations        ← implemented by LockTaskController
  ├── interface WorkSchedulingOperations  ← implemented by WorkScheduler
  ├── interface ProvisioningOperations    ← implemented by ProvisioningController
  ├── interface StatePersistence          ← implemented by ComplianceStateStore + EnforcementStateStore
  ├── interface TimerOperations           ← implemented by CountdownTimer
  ├── interface EventEmitter              ← implemented by LockEventChannel
  └── interface ActivityLauncher          ← implemented by ActivityLauncher (fun interface)
```

`MainActivity` constructs all concrete implementations and injects them into `EnforcementEngine`.

---

## 3. Class Diagram

### 3.1 Engine Core

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          engine/                                         │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                    EnforcementEngine                               │  │
│  │                    (public facade)                                 │  │
│  │                                                                   │  │
│  │  - stateManager: StateManager                                     │  │
│  │  - policyManager: PolicyManager                                   │  │
│  │  - lifecycleManager: LifecycleManager                             │  │
│  │  - actionExecutor: ActionExecutor                                 │  │
│  │  - eventDispatcher: EventDispatcher                               │  │
│  │                                                                   │  │
│  │  + submit(event: EnforcementEvent)                                │  │
│  │  + getState(): EnforcementState                                   │  │
│  │  + start()                                                        │  │
│  │  + stop()                                                         │  │
│  │                                                                   │  │
│  │  private processEvent(event):                                     │  │
│  │    1. actions = policyManager.evaluate(stateManager.state, event) │  │
│  │    2. for each action: actionExecutor.execute(action)             │  │
│  │    3. stateManager.applyStateChanges()                            │  │
│  └──────┬──────────────┬──────────────┬──────────────┬───────────────┘  │
│         │              │              │              │                   │
│         ▼              ▼              ▼              ▼                   │
│  ┌──────────────┐ ┌──────────────┐ ┌────────────┐ ┌──────────────────┐ │
│  │EventDispatcher│ │StateManager  │ │PolicyManager│ │  ActionExecutor  │ │
│  │              │ │              │ │            │ │                  │ │
│  │- engine:     │ │- state:      │ │- policy:   │ │- controllers:    │ │
│  │  Engine?     │ │  Enforcement │ │  Enforcement│ │  DeviceAdminOps  │ │
│  │- buffer:     │ │  State       │ │  Policy     │ │  LocationOps     │ │
│  │  Queue<Event>│ │- store:      │ │            │ │  UninstallOps    │ │
│  │              │ │  StatePersist│ │+ evaluate(  │ │  LockTaskOps     │ │
│  │+ attach(eng) │ │              │ │    state,   │ │  WorkSchedOps    │ │
│  │+ detach()    │ │+ transition( │ │    event)   │ │  ProvisioningOps │ │
│  │+ dispatch(   │ │    event)    │ │    : List<  │ │- lifecycle:      │ │
│  │    event)    │ │    : State   │ │    Action>  │ │  LifecycleManager│ │
│  │+ flush()     │ │+ state: State│ │            │ │- emitter:        │ │
│  │              │ │+ persist()   │ │            │ │  EventEmitter    │ │
│  │ (object —    │ │+ restore()   │ │            │ │                  │ │
│  │  static access│ │              │ │            │ │+ execute(action) │ │
│  │  for system-  │ │ (pure logic —│ │            │ │  routes to       │ │
│  │  instantiated │ │  no Android  │ │            │ │  appropriate     │ │
│  │  components)  │ │  deps except  │ │            │ │  controller/     │ │
│  │              │ │  store)       │ │            │ │  lifecycle/      │ │
│  └──────────────┘ └──────────────┘ └────────────┘ │  emitter          │ │
│                                                   └──────────────────┘ │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                    LifecycleManager                                │  │
│  │                                                                    │  │
│  │  - context: Context                                                │  │
│  │  - timer: TimerOperations                                          │  │
│  │  - activityLauncher: ActivityLauncher                              │  │
│  │                                                                    │  │
│  │  + startEnforcementService(action: ServiceAction)                  │  │
│  │  + stopEnforcementService()                                        │  │
│  │  + startComplianceActivity()                                       │  │
│  │  + finishComplianceActivity()                                      │  │
│  │  + startGraceTimer(durationMs: Long, callback: () -> Unit)         │  │
│  │  + startLockCountdown(durationMs: Long, callback: () -> Unit)      │  │
│  │  + cancelAllTimers()                                               │  │
│  │  + registerUserPresentReceiver()                                   │  │
│  │  + unregisterUserPresentReceiver()                                 │  │
│  │  + schedulePeriodicReapply()                                       │  │
│  │  + cancelPeriodicReapply()                                         │  │
│  │  + startLocationWatch()                                            │  │
│  │  + stopLocationWatch()                                             │  │
│  └────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Event Sources (Receivers)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          events/                                        │
│                                                                         │
│  ┌──────────────────────────────┐  ┌──────────────────────────────┐     │
│  │ LocationProviderChange       │  │ UserPresentReceiver          │     │
│  │ Receiver                     │  │ (CONSOLIDATED — replaces     │     │
│  │ : BroadcastReceiver          │  │  both LockoutManager's       │     │
│  │                              │  │  internal receiver AND       │     │
│  │ onReceive(ctx, intent):      │  │  LocationComplianceUnlock    │     │
│  │   EventDispatcher.dispatch(  │  │  Receiver)                   │     │
│  │     LocationProviderChanged) │  │                              │     │
│  │                              │  │ : BroadcastReceiver          │     │
│  │ companion:                   │  │                              │     │
│  │   register(ctx)              │  │ onReceive(ctx, intent):      │     │
│  │   unregister(ctx)            │  │   EventDispatcher.dispatch(  │     │
│  │   (RECEIVER_EXPORTED)        │  │     UserPresent)             │     │
│  └──────────────────────────────┘  │                              │     │
│                                     │ companion:                   │     │
│  ┌──────────────────────────────┐  │   register(ctx)              │     │
│  │ BootCompletedReceiver        │  │   unregister(ctx)            │     │
│  │ : BroadcastReceiver          │  │   (RECEIVER_EXPORTED ✓)       │     │
│  │                              │  └──────────────────────────────┘     │
│  │ onReceive(ctx, intent):      │                                       │
│  │   EventDispatcher.dispatch(  │  ┌──────────────────────────────┐     │
│  │     BootCompleted)           │  │ DeviceAdminEventReceiver     │     │
│  │                              │  │ : DeviceAdminReceiver        │     │
│  │ (manifest-registered)        │  │                              │     │
│  └──────────────────────────────┘  │ onEnabled(ctx, intent):      │     │
│                                     │   dispatch(DeviceAdminEnabled)│    │
│                                     │                              │     │
│                                     │ onDisabled(ctx, intent):     │     │
│                                     │   dispatch(DeviceAdminDisabled)│   │
│                                     │                              │     │
│                                     │ onProfileProvisioningComplete│     │
│                                     │   (ctx, intent):             │     │
│                                     │   dispatch(                  │     │
│                                     │     ProfileProvisioningComplete)│  │
│                                     │                              │     │
│                                     │ onDisableRequested:          │     │
│                                     │   return warning text        │     │
│                                     │   (no business decision)     │     │
│                                     └──────────────────────────────┘     │
└─────────────────────────────────────────────────────────────────────────┘
```

**Key change:** All receivers use `RECEIVER_EXPORTED` for system broadcasts. The `UserPresentReceiver` consolidates the two previous receivers that listened for `ACTION_USER_PRESENT` with inconsistent export flags.

### 3.3 Service

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          service/                                       │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │              EnforcementForegroundService                          │  │
│  │              (CONSOLIDATED — replaces both                         │  │
│  │               AppLockForegroundService AND                        │  │
│  │               LocationComplianceForegroundService)                │  │
│  │              : Service()                                          │  │
│  │                                                                   │  │
│  │  companion:                                                       │  │
│  │    var isRunning: Boolean  (informational only — not for decisions)│ │
│  │                                                                   │  │
│  │  onCreate():                                                      │  │
│  │    isRunning = true                                               │  │
│  │                                                                   │  │
│  │  onStartCommand(intent, flags, startId):                          │  │
│  │    ServiceGuard.startForeground(this)  // ALWAYS — null-intent safe│ │
│  │    if (intent == null || intent.action == null):                  │  │
│  │      EventDispatcher.dispatch(ServiceRestarted)                   │  │
│  │    else:                                                          │  │
│  │      EventDispatcher.dispatch(ServiceStarted(action))             │  │
│  │    return START_STICKY                                            │  │
│  │                                                                   │  │
│  │  onDestroy():                                                     │  │
│  │    isRunning = false                                              │  │
│  │    EventDispatcher.dispatch(ServiceStopped)                       │  │
│  │                                                                   │  │
│  │  // NO business logic. NO controller calls. NO state reads.       │  │
│  │  // The engine decides what to do with ServiceStarted/Restarted.  │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │  ServiceGuard                                                      │  │
│  │                                                                   │  │
│  │  + startForeground(service: Service)                              │  │
│  │    // Creates generic notification, calls service.startForeground │  │
│  │    // within 5 seconds — crash-safe for null-intent restarts      │  │
│  │                                                                   │  │
│  │  + updateNotification(service: Service, contentTitle, contentText)│  │
│  │    // Updates the ongoing foreground notification                 │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.4 Controllers (Command Executors)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          controller/                                    │
│                                                                         │
│  ┌──────────────────────────────┐  ┌──────────────────────────────┐     │
│  │ DeviceAdminController        │  │ LocationController            │     │
│  │ : DeviceAdminOperations      │  │ : LocationOperations          │     │
│  │                              │  │                              │     │
│  │ - context: Context           │  │ - context: Context           │     │
│  │ - dpm: DevicePolicyManager   │  │ - lm: LocationManager        │     │
│  │                              │  │                              │     │
│  │ + lockNow(): Boolean         │  │ + forceLocationOn(): Boolean │     │
│  │ + lockForDuration(ms): Bool  │  │ + isLocationEnabled(): Bool  │     │
│  │ + setUninstallBlocked(b)     │  │                              │     │
│  │ + isUninstallBlocked(): Bool │  │ // NO event emission         │     │
│  │ + setUserRestriction(...)    │  │ // NO state decisions        │     │
│  │ + isDeviceOwner(): Boolean   │  │ // Pure command execution    │     │
│  │                              │  └──────────────────────────────┘     │
│  │ // NO event emission         │                                       │
│  │ // NO state decisions        │  ┌──────────────────────────────┐     │
│  │ // Pure command execution    │  │ LocationWatchController       │     │
│  └──────────────────────────────┘  │ : LocationWatchOperations     │     │
│                                     │                              │     │
│  ┌──────────────────────────────┐  │ - receiver:                  │     │
│  │ UninstallController          │  │   LocationProviderChangeRecv │     │
│  │ : UninstallOperations        │  │ - registered: Boolean        │     │
│  │                              │  │                              │     │
│  │ - context: Context           │  │ + startWatch()               │     │
│  │ - dpm: DevicePolicyManager   │  │ + stopWatch()                │     │
│  │                              │  │                              │     │
│  │ + setBlocked(b): Boolean     │  │ // Manages receiver reg/unreg│     │
│  │ + isBlocked(): Boolean       │  │ // SINGLE instance — no leaks│     │
│  │ + isSupported(): Boolean     │  │ // Receiver emits events,    │     │
│  │                              │  │ // doesn't call coordinator   │     │
│  │ // NO LocationEnforcementCtrl│  └──────────────────────────────┘     │
│  │ // NO side-effects           │                                       │
│  │ // Pure command execution    │  ┌──────────────────────────────┐     │
│  └──────────────────────────────┘  │ LockTaskController            │     │
│                                     │ : LockTaskOperations          │     │
│  ┌──────────────────────────────┐  │                              │     │
│  │ WorkScheduler                │  │ - context: Context           │     │
│  │ : WorkSchedulingOperations   │  │ - dpm: DevicePolicyManager   │     │
│  │                              │  │                              │     │
│  │ - context: Context           │  │ + arm(): Boolean             │     │
│  │                              │  │ + disarm(): Boolean          │     │
│  │ + scheduleReapply()          │  │ + isSupported(): Boolean     │     │
│  │ + cancelReapply()            │  │                              │     │
│  │ + isScheduled(): Boolean     │  │ // Pure command execution    │     │
│  │                              │  └──────────────────────────────┘     │
│  │ // Uses UPDATE policy        │                                       │
│  │ // (not KEEP)                │  ┌──────────────────────────────┐     │
│  └──────────────────────────────┘  │ ProvisioningController        │     │
│                                     │ : ProvisioningOperations     │     │
│                                     │                              │     │
│                                     │ - launcher: ActivityLauncher │     │
│                                     │                              │     │
│                                     │ + launchProvisioning(): Bool │     │
│                                     │ + isProvisioningAllowed()    │     │
│                                     └──────────────────────────────┘     │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.5 UI (Compliance Activity)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          ui/                                            │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │              LocationComplianceActivity                           │  │
│  │              : ComponentActivity()                                │  │
│  │                                                                   │  │
│  │  // NO controller construction                                    │  │
│  │  // NO service starting                                           │  │
│  │  // NO state transitions                                          │  │
│  │                                                                   │  │
│  │  onCreate():                                                      │  │
│  │    setShowWhenLocked(true)                                        │  │
│  │    setTurnScreenOn(true)                                          │  │
│  │    // Query engine for current state to display                   │  │
│  │    val state = EnforcementEngine.getState()                       │  │
│  │    displayState(state)                                            │  │
│  │    startLockTaskMode()  // if engine says to                      │  │
│  │                                                                   │  │
│  │  // Polls engine state for countdown display                      │  │
│  │  // (or receives state updates via callback)                      │  │
│  │                                                                   │  │
│  │  onDismissButton():                                               │  │
│  │    EventDispatcher.dispatch(ComplianceScreenDismissed)            │  │
│  │                                                                   │  │
│  │  onDestroy():                                                     │  │
│  │    EventDispatcher.dispatch(ComplianceScreenDestroyed)            │  │
│  │                                                                   │  │
│  │  // Back button consumed via callback (non-dismissible)           │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.6 Channel Bridge

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          channel/                                       │
│                                                                         │
│  ┌──────────────────────────────┐  ┌──────────────────────────────┐     │
│  │ LockMethodChannel            │  │ LockEventChannel              │     │
│  │ : MethodChannel.MethodCallHnd│  │ : EventChannel.StreamHandler  │     │
│  │                              │  │ : EventEmitter                │     │
│  │ - handlers: Map<String,      │  │                              │     │
│  │     Handler>                 │  │ - mainHandler: Handler        │     │
│  │                              │  │   (Looper.getMainLooper)      │     │
│  │ onMethodCall(call, result):  │  │ - sink: EventSink?            │     │
│  │   handler = handlers[         │  │                              │     │
│  │     call.method]             │  │ + emitLockResult(result)      │     │
│  │   handler.handle(call, result│  │ + emitServiceStateChanged(s)  │     │
│  │                              │  │ + emitLockoutStateChanged(b)  │     │
│  │ // Does NOT execute commands │  │ + emitPermissionChanged(b)    │     │
│  │ // Does NOT read state       │  │ + emitUninstallBlockChanged(b)│     │
│  │ // Creates event, dispatches │  │                              │     │
│  └──────────────┬───────────────┘  │ // ALL emissions posted to    │     │
│                 │                  │ // main thread — thread-safe  │     │
│  ┌──────────────▼───────────────┐  └──────────────────────────────┘     │
│  │ Handlers (thin adapters)     │                                       │
│  │                              │  ┌──────────────────────────────┐     │
│  │ AppLockHandler:              │  │ EventEmitter (interface)     │     │
│  │   lockNow() →                │  │                              │     │
│  │     dispatch(LockNowRequested│  │ + emitLockResult(String)     │     │
│  │   lockForDuration(ms) →      │  │ + emitServiceStateChanged(s) │     │
│  │     dispatch(LockForDuration │  │ + emitLockoutStateChanged(b) │     │
│  │       Requested(ms))         │  │ + emitPermissionChanged(b)   │     │
│  │   startService(ms) →         │  │ + emitUninstallBlockChanged  │     │
│  │     dispatch(StartService    │  └──────────────────────────────┘     │
│  │       Requested(ms))         │                                       │
│  │   stopService() →            │                                       │
│  │     dispatch(StopService     │                                       │
│  │       Requested)             │                                       │
│  │                              │                                       │
│  │ PermissionHandler:           │                                       │
│  │   checkPermission() →        │                                       │
│  │     return engine.getState() │                                       │
│  │       .permissionGranted     │                                       │
│  │   requestPermission() →      │                                       │
│  │     dispatch(RequestPermission│                                      │
│  │       Requested)             │                                       │
│  │                              │                                       │
│  │ UninstallHandler:            │                                       │
│  │   setBlocked(b) →            │                                       │
│  │     dispatch(SetUninstall    │                                       │
│  │       BlockedRequested(b))   │                                       │
│  │   isBlocked() →              │                                       │
│  │     return engine.getState() │                                       │
│  │       .uninstallBlocked      │                                       │
│  └──────────────────────────────┘                                       │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.7 Persistence & Timer

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          persistence/                                   │
│                                                                         │
│  ┌──────────────────────────────┐  ┌──────────────────────────────┐     │
│  │ ComplianceStateStore         │  │ EnforcementStateStore         │     │
│  │ : StatePersistence            │  │ : StatePersistence            │     │
│  │                              │  │                              │     │
│  │ - prefs: SharedPreferences   │  │ - prefs: SharedPreferences   │     │
│  │   ("compliance_state.xml")   │  │   ("enforcement_state.xml")  │     │
│  │                              │  │                              │     │
│  │ + getComplianceState():      │  │ + getLockoutActive(): Bool   │     │
│  │     ComplianceState          │  │ + setLockoutActive(b)        │     │
│  │ + setComplianceState(s)      │  │ + getLockoutDeadline(): Long │     │
│  │ + getCountdownDeadline()     │  │ + setLockoutDeadline(ms)     │     │
│  │ + setCountdownDeadline(ms)   │  │ + getServiceRunning(): Bool  │     │
│  │ + getLockIteration(): Int    │  │ + setServiceRunning(b)       │     │
│  │ + setLockIteration(i)        │  │ + getPermissionGranted()     │     │
│  │ + isEnforcementArmed(): Bool │  │ + setPermissionGranted(b)    │     │
│  │ + setEnforcementArmed(b)     │  │ + getUninstallBlocked()      │     │
│  │ + clear()                    │  │ + setUninstallBlocked(b)     │     │
│  └──────────────────────────────┘  └──────────────────────────────┘     │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │  timer/                                                            │  │
│  │                                                                    │  │
│  │  ┌─────────────────────────────────────────────────────────────┐  │  │
│  │  │ CountdownTimer : TimerOperations                           │  │  │
│  │  │                                                             │  │  │
│  │  │ - handler: Handler                                         │  │  │
│  │  │ - runnable: Runnable?  (stored — surgical cancel)          │  │  │
│  │  │ - onExpire: (() -> Unit)?                                  │  │  │
│  │  │                                                             │  │  │
│  │  │ + start(durationMs: Long, onExpire: () -> Unit)            │  │  │
│  │  │ + cancel()  // removeCallbacks(runnable) — NOT null        │  │  │
│  │  │ + isRunning(): Boolean                                     │  │  │
│  │  └─────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.8 Worker

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          worker/                                        │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │  ReapplyWorker : CoroutineWorker                                  │  │
│  │                                                                   │  │
│  │  doWork(): Result                                                 │  │
│  │    EventDispatcher.dispatch(PeriodicReapplyTriggered)             │  │
│  │    return Result.success()                                        │  │
│  │                                                                   │  │
│  │  // Does NOT construct LocationEnforcementController              │  │
│  │  // Does NOT call controller.apply()                              │  │
│  │  // Does NOT read/write state                                     │  │
│  │  // Just emits event — engine decides what to do                  │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.9 Composition Root

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          MainActivity                                   │
│                          (Composition Root)                             │
│                                                                         │
│  configureFlutterEngine(engine):                                        │
│                                                                         │
│    // 1. Create persistence                                             │
│    complianceStore = ComplianceStateStore(context)                      │
│    enforcementStore = EnforcementStateStore(context)                    │
│                                                                         │
│    // 2. Create controllers (concrete implementations)                  │
│    deviceAdminCtrl = DeviceAdminController(context)                     │
│    locationCtrl = LocationController(context)                           │
│    locationWatchCtrl = LocationWatchController(context)                 │
│    uninstallCtrl = UninstallController(context)                         │
│    lockTaskCtrl = LockTaskController(context)                           │
│    workScheduler = WorkScheduler(context)                               │
│    activityLauncher = ActivityLauncher { intent -> startActivity(...) } │
│    provisioningCtrl = ProvisioningController(activityLauncher)          │
│                                                                         │
│    // 3. Create timer                                                   │
│    countdownTimer = CountdownTimer(Handler(mainLooper))                 │
│                                                                         │
│    // 4. Create event channel (EventEmitter)                           │
│    eventChannel = LockEventChannel()                                    │
│                                                                         │
│    // 5. Create lifecycle manager                                       │
│    lifecycleManager = LifecycleManager(                                 │
│      context, countdownTimer, activityLauncher)                         │
│                                                                         │
│    // 6. Create action executor                                         │
│    actionExecutor = ActionExecutor(                                     │
│      deviceAdminCtrl, locationCtrl, locationWatchCtrl,                  │
│      uninstallCtrl, lockTaskCtrl, workScheduler,                        │
│      provisioningCtrl, lifecycleManager, eventChannel)                  │
│                                                                         │
│    // 7. Create state manager                                           │
│    stateManager = StateManager(complianceStore, enforcementStore)       │
│                                                                         │
│    // 8. Create policy manager                                          │
│    policyManager = PolicyManager(EnforcementPolicy.default)             │
│                                                                         │
│    // 9. Create enforcement engine                                      │
│    enforcementEngine = EnforcementEngine(                               │
│      stateManager, policyManager, lifecycleManager,                     │
│      actionExecutor)                                                    │
│                                                                         │
│    // 10. Attach engine to dispatcher (enables event processing)        │
│    EventDispatcher.attach(enforcementEngine)                            │
│                                                                         │
│    // 11. Restore state from persistence                                │
│    enforcementEngine.start()  // restores state, registers receivers    │
│                                                                         │
│    // 12. Wire Flutter channels                                         │
│    LockMethodChannel.register(messenger, enforcementEngine)             │
│    eventChannel.register(messenger)                                     │
│                                                                         │
│  onDestroy():                                                           │
│    enforcementEngine.stop()                                             │
│    EventDispatcher.detach()                                             │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Responsibilities

### 4.1 EnforcementEngine (Facade)

| Responsibility | Description |
|----------------|-------------|
| **Single entry point** | All external components interact with the engine through `submit(event)` or `getState()` |
| **Event processing** | Receives events from `EventDispatcher`, orchestrates evaluation and execution |
| **Action orchestration** | Calls `PolicyManager.evaluate()` to get actions, then `ActionExecutor.execute()` for each |
| **State synchronization** | After actions are executed, persists state changes via `StateManager` |
| **Lifecycle management** | `start()` restores state and registers receivers; `stop()` cleans up |

**What it does NOT do:**
- Does not directly call Android APIs (DPM, LocationManager, etc.) — delegates to controllers
- Does not directly start services/activities — delegates to `LifecycleManager`
- Does not directly emit to Flutter — delegates to `EventEmitter` via `ActionExecutor`

### 4.2 EventDispatcher

| Responsibility | Description |
|-----------------|-------------|
| **Event reception** | Accepts events from any thread (receivers, services, handlers, workers, activity) |
| **Event buffering** | If no engine is attached (process restart window), buffers events and flushes on attach |
| **Event serialization** | Posts events to a single-threaded executor to ensure serial processing |
| **Static access** | `object` singleton — necessary because system-instantiated components (receivers, services) cannot receive constructor injection |

**What it does NOT do:**
- Does not evaluate events
- Does not execute actions
- Does not read/write state
- Does not make any decisions

**Design note:** `EventDispatcher` is the ONLY `object` singleton with mutable state (besides `Constants` which is stateless). This is acceptable because:
1. It is a dumb pipe — no business logic
2. System-instantiated components need static access
3. It buffers events during the process-restart window, solving the lost-events problem

### 4.3 StateManager

| Responsibility | Description |
|-----------------|-------------|
| **State ownership** | Single source of truth for all enforcement state (compliance, lockout, service, permission, uninstall) |
| **State transitions** | Applies state changes resulting from policy evaluation |
| **State persistence** | Persists state to `ComplianceStateStore` and `EnforcementStateStore` |
| **State restoration** | On `start()`, reads persisted state and reconstructs in-memory state |
| **State queries** | Provides `state` property for engine and UI to read current state |

**What it does NOT do:**
- Does not decide which state to transition to (that's `PolicyManager`)
- Does not execute commands (that's `ActionExecutor`)
- Does not manage lifecycle (that's `LifecycleManager`)

**State model:**
```
EnforcementState:
  complianceState: ComplianceState     // NORMAL, LOCATION_OFF, COUNTDOWN_RUNNING, ...
  lockoutActive: Boolean
  lockoutDeadline: Long?
  serviceRunning: Boolean
  serviceAction: ServiceAction?
  permissionGranted: Boolean
  uninstallBlocked: Boolean
  enforcementArmed: Boolean
  lockIteration: Int
  countdownDeadline: Long?
```

### 4.4 PolicyManager

| Responsibility | Description |
|-----------------|-------------|
| **Decision making** | The "brain" — evaluates `(state, event)` pairs and returns `List<EnforcementAction>` |
| **Policy configuration** | Holds `EnforcementPolicy` with all configurable parameters (grace periods, lockout duration, etc.) |
| **Pure logic** | No Android dependencies, no I/O — takes state + event, returns actions. Fully unit-testable. |
| **Stateless** | Does not hold or mutate state — reads state from `StateManager`, returns actions for engine to execute |

**What it does NOT do:**
- Does not execute actions
- Does not persist state
- Does not call controllers
- Does not manage lifecycle

**Policy evaluation example (conceptual):**
```
evaluate(state, event):
  when (event):
    LocationProviderChanged:
      if (state.permissionGranted && state.enforcementArmed):
        if (!locationController.isLocationEnabled()):  // ← query via injected checker
          return [TransitionTo(LOCATION_OFF),
                  StartGraceTimer(policy.gracePeriodMs),
                  StartEnforcementService]
        else:
          return []  // location is on, nothing to do
    
    GracePeriodExpired:
      if (state.complianceState == LOCATION_OFF):
        return [TransitionTo(COUNTDOWN_RUNNING),
                StartLockCountdown(policy.lockCountdownMs),
                StartComplianceActivity,
                ExecuteArmLockTask]
    
    LockCountdownExpired:
      return [TransitionTo(LOCKED),
              ExecuteLockNow,
              FinishComplianceActivity,
              EmitLockResult("locked")]
    
    UserPresent:
      if (state.lockoutActive):
        return [ExecuteLockNow,
                EmitLockResult("locked")]
      else:
        return []  // no lockout, nothing to do
    
    BootCompleted:
      if (state.enforcementArmed):
        return [StartEnforcementService,
                StartLocationWatch,
                SchedulePeriodicReapply]
      else:
        return []
    
    ServiceRestarted:
      return [StartLocationWatch,  // re-register receiver
              SchedulePeriodicReapply]  // re-schedule worker
    
    LockNowRequested:
      return [ExecuteLockNow,
              EmitLockResult("locked")]
    
    // ... etc
```

### 4.5 LifecycleManager

| Responsibility | Description |
|-----------------|-------------|
| **Service management** | Starts/stops `EnforcementForegroundService` with appropriate actions |
| **Activity management** | Starts/finishes `LocationComplianceActivity` |
| **Timer management** | Starts/cancels grace period and lock countdown timers |
| **Receiver management** | Registers/unregisters `UserPresentReceiver` and `LocationProviderChangeReceiver` |
| **Work management** | Schedules/cancels periodic reapply work |
| **Location watch** | Starts/stops realtime location watch via `LocationWatchController` |

**What it does NOT do:**
- Does not decide WHEN to start/stop (that's `PolicyManager`)
- Does not evaluate state
- Does not execute DPM commands (that's controllers)

### 4.6 ActionExecutor

| Responsibility | Description |
|-----------------|-------------|
| **Action routing** | Receives `EnforcementAction` from engine, routes to the correct executor |
| **Command execution** | Calls the appropriate controller for command actions |
| **Lifecycle delegation** | Calls `LifecycleManager` for lifecycle actions |
| **Notification emission** | Calls `EventEmitter` for notification actions |
| **State transition** | Calls `StateManager` for state transition actions |

**What it does NOT do:**
- Does not decide which actions to execute (that's `PolicyManager`)
- Does not make business decisions

### 4.7 Receivers (Event Sources)

| Receiver | Event(s) Emitted | Business Decisions Made |
|----------|------------------|----------------------|
| `LocationProviderChangeReceiver` | `LocationProviderChanged` | **NONE** |
| `UserPresentReceiver` | `UserPresent` | **NONE** |
| `BootCompletedReceiver` | `BootCompleted` | **NONE** |
| `DeviceAdminEventReceiver` | `DeviceAdminEnabled`, `DeviceAdminDisabled`, `ProfileProvisioningComplete` | **NONE** |

**Before (current code):**
- `LocationComplianceBootReceiver` checked `store.isEnforcementArmed()` and started a service — a business decision
- `AppLockDeviceAdminReceiver.enforceLocation()` constructed a controller and called `apply()` — a business decision
- `LockoutManager.userPresentReceiver` called `onUserPresent()` which re-locked — a business decision

**After (new design):**
- All receivers do exactly one thing: `EventDispatcher.dispatch(Event)`. The engine decides what to do.

### 4.8 EnforcementForegroundService (Process Keep-Alive)

| Responsibility | Description |
|----------------|-------------|
| **Foreground notification** | Calls `startForeground()` immediately in `onStartCommand` (null-intent safe) |
| **Process keep-alive** | Keeps the process alive via `START_STICKY` |
| **Lifecycle events** | Emits `ServiceStarted`, `ServiceRestarted`, `ServiceStopped` events |

**What it does NOT do:**
- Does NOT decide what action to perform
- Does NOT call controllers
- Does NOT read/write state
- Does NOT start countdowns or timers
- Does NOT arm lockout

### 4.9 Controllers (Command Executors)

| Controller | Commands | Decisions Made |
|------------|----------|----------------|
| `DeviceAdminController` | `lockNow()`, `lockForDuration()`, `setUninstallBlocked()`, `setUserRestriction()` | **NONE** |
| `LocationController` | `forceLocationOn()`, `isLocationEnabled()` | **NONE** |
| `LocationWatchController` | `startWatch()`, `stopWatch()` | **NONE** |
| `UninstallController` | `setBlocked()`, `isBlocked()`, `isSupported()` | **NONE** |
| `LockTaskController` | `arm()`, `disarm()`, `isSupported()` | **NONE** |
| `WorkScheduler` | `scheduleReapply()`, `cancelReapply()` | **NONE** |
| `ProvisioningController` | `launchProvisioning()`, `isProvisioningAllowed()` | **NONE** |

**Before (current code):**
- `UninstallBlockController.setBlocked()` called `locationEnforcementController.apply()` — a business decision (side effect)
- `LocationEnforcementController` decided to schedule WorkManager, force location, apply restrictions — business decisions

**After (new design):**
- All controllers are pure command executors. They execute the command given to them and return a result. No side effects, no decisions.

### 4.10 LocationComplianceActivity (UI)

| Responsibility | Description |
|----------------|-------------|
| **State display** | Queries engine for current state, displays countdown and location status |
| **Lock Task Mode** | Arms/disarms Lock Task Mode when engine instructs (via state) |
| **UI events** | Emits `ComplianceScreenDismissed`, `ComplianceScreenDestroyed` events |

**What it does NOT do:**
- Does NOT construct controllers
- Does NOT start services
- Does NOT transition compliance state
- Does NOT call DPM operations

### 4.11 Channel Handlers (Transport)

| Handler | Events Created | Decisions Made |
|---------|---------------|----------------|
| `AppLockHandler` | `LockNowRequested`, `LockForDurationRequested`, `StartServiceRequested`, `StopServiceRequested` | **NONE** |
| `PermissionHandler` | `RequestPermissionRequested` | **NONE** |
| `UninstallHandler` | `SetUninstallBlockedRequested` | **NONE** |

**Before (current code):**
- `AppLockHandler.lockNow()` called `DeviceAdminController.lockNow()` directly — executing a command
- `PermissionHandler.requestPermission()` launched the provisioning intent directly — executing a command

**After (new design):**
- All handlers create an event and dispatch it to the engine. The engine decides whether to execute the command.

### 4.12 ReapplyWorker

| Responsibility | Description |
|----------------|-------------|
| **Event emission** | Emits `PeriodicReapplyTriggered` event on each WorkManager cycle |

**What it does NOT do:**
- Does NOT construct `LocationEnforcementController`
- Does NOT call `controller.apply()`
- Does NOT read/write state

---

## 5. Sequence Diagrams

### 5.1 "Lock Now" Button Tap (Flutter Request)

```
Flutter UI          MethodChannel      AppLockHandler     EventDispatcher     EnforcementEngine    PolicyManager     ActionExecutor     DeviceAdminCtrl     EventChannel
    │                    │                   │                   │                   │                   │                   │                   │                   │
    │  tap "Lock Now"    │                   │                   │                   │                   │                   │                   │                   │
    ├───────────────────►│                   │                   │                   │                   │                   │                   │                   │
    │                    │  invoke("lockNow")│                   │                   │                   │                   │                   │                   │
    │                    ├──────────────────►│                   │                   │                   │                   │                   │                   │
    │                    │                   │                   │                   │                   │                   │                   │                   │
    │                    │                   │  dispatch(        │                   │                   │                   │                   │                   │
    │                    │                   │   LockNowRequested)│                   │                   │                   │                   │                   │
    │                    │                   ├──────────────────►│                   │                   │                   │                   │                   │
    │                    │                   │                   │  submit(event)    │                   │                   │                   │                   │
    │                    │                   │                   ├──────────────────►│                   │                   │                   │                   │
    │                    │                   │                   │                   │                   │                   │                   │                   │
    │                    │                   │                   │                   │  evaluate(state,  │                   │                   │                   │
    │                    │                   │                   │                   │   event)          │                   │                   │                   │
    │                    │                   │                   │                   ├──────────────────►│                   │                   │                   │
    │                    │                   │                   │                   │                   │                   │                   │                   │
    │                    │                   │                   │                   │  [ExecuteLockNow, │                   │                   │                   │
    │                    │                   │                   │                   │   EmitLockResult] │                   │                   │                   │
    │                    │                   │                   │                   │◄──────────────────┤                   │                   │                   │
    │                    │                   │                   │                   │                   │                   │                   │                   │
    │                    │                   │                   │                   │  execute(         │                   │                   │                   │
    │                    │                   │                   │                   │   ExecuteLockNow) │                   │                   │                   │
    │                    │                   │                   │                   ├──────────────────────────────────────────────────────────►│                   │                   │
    │                    │                   │                   │                   │                   │                   │                   │  lockNow()        │
    │                    │                   │                   │                   │                   │                   │                   ├──────────────────►│
    │                    │                   │                   │                   │                   │                   │                   │  DPM.lockNow()    │
    │                    │                   │                   │                   │                   │                   │                   │◄──────────────────┤
    │                    │                   │                   │                   │                   │                   │◄──────────────────┤  return true      │
    │                    │                   │                   │                   │                   │                   │                   │                   │
    │                    │                   │                   │                   │  execute(         │                   │                   │                   │
    │                    │                   │                   │                   │   EmitLockResult) │                   │                   │                   │
    │                    │                   │                   │                   │   ..............  │                   │                   │                   │
    │                    │                   │                   │                   │   (routes to      │                   │                   │                   │
    │                    │                   │                   │                   │    EventEmitter)  │                   │                   │                   │
    │                    │                   │                   │                   ├──────────────────────────────────────────────────────────────────────────────►│
    │                    │                   │                   │                   │                   │                   │                   │  emitLockResult   │
    │                    │                   │                   │                   │                   │                   │                   │  (main thread)    │
    │                    │                   │                   │                   │                   │                   │                   │  sink.success()   │
    │                    │                   │                   │                   │                   │                   │                   │◄──────────────────┤
    │                    │                   │                   │                   │                   │                   │                   │                   │
    │                    │  result(true)     │                   │                   │                   │                   │                   │                   │
    │                    │◄──────────────────┤                   │                   │                   │                   │                   │                   │
    │  UI updates        │                   │                   │                   │                   │                   │                   │                   │
    │◄───────────────────┤                   │                   │                   │                   │                   │                   │                   │
    │                    │                   │                   │                   │                   │                   │                   │                   │
    │                    │                   │                   │                   │                   │                   │                   │  EventChannel     │
    │                    │                   │                   │                   │                   │                   │                   │  pushes "locked"  │
    │                    │                   │                   │                   │                   │                   │                   │  to Flutter       │
    │                    │                   │                   │                   │                   │                   │                   │                   │
```

**Key point:** The handler does NOT call `DeviceAdminController.lockNow()` directly. It creates a `LockNowRequested` event. The engine's `PolicyManager` decides to execute `ExecuteLockNow` + `EmitLockResult`. The `ActionExecutor` routes the command to `DeviceAdminController` and the notification to `EventChannel`.

---

### 5.2 Location Turned Off (Receiver Event)

```
System              LocationProvider    EventDispatcher     EnforcementEngine    PolicyManager     StateManager     ActionExecutor     LifecycleMgr     LocationCtrl     LockTaskCtrl     EventChannel
                       ChangeReceiver
    │                       │                   │                   │                   │                   │                   │                   │                   │                   │                   │
    │  PROVIDERS_CHANGED    │                   │                   │                   │                   │                   │                   │                   │                   │                   │
    ├──────────────────────►│                   │                   │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │                   │                   │
    │                       │  dispatch(        │                   │                   │                   │                   │                   │                   │                   │                   │
    │                       │   LocationProvider│                   │                   │                   │                   │                   │                   │                   │                   │
    │                       │   Changed)        │                   │                   │                   │                   │                   │                   │                   │                   │
    │                       ├──────────────────►│                   │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │  submit(event)    │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   ├──────────────────►│                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  evaluate(state,  │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │   event)          │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   ├──────────────────►│                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │  // Policy checks:│                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │  // 1. Is permission granted?                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │  // 2. Is enforcement armed?                    │                   │                   │                   │                   │
    │                       │                   │                   │                   │  // 3. Query LocationCtrl.isLocationEnabled()   │                   │                   │                   │                   │
    │                       │                   │                   │                   │  //    → false (location is off)                │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  [TransitionTo(LOCATION_OFF),              │                   │                   │                   │                   │                   │
    │                       │                   │                   │   StartGraceTimer(120000),                  │                   │                   │                   │                   │                   │
    │                       │                   │                   │   StartEnforcementService]                  │                   │                   │                   │                   │                   │
    │                       │                   │                   │◄──────────────────┤                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  execute(TransitionTo(LOCATION_OFF))       │                   │                   │                   │                   │                   │
    │                       │                   │                   ├──────────────────────────────────────────►│                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │  transition()     │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │  persist()        │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │◄──────────────────┤                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  execute(StartGraceTimer(120000))          │                   │                   │                   │                   │                   │
    │                       │                   │                   ├──────────────────────────────────────────────────────────────►│                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │  startGraceTimer  │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │  (120000ms,       │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │   callback→      │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │   dispatch(      │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │    GraceExpired))│                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │◄──────────────────┤                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  execute(StartEnforcementService)         │                   │                   │                   │                   │                   │
    │                       │                   │                   ├──────────────────────────────────────────────────────────────►│                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │  startService()   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │  (EnforcementFG)  │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │◄──────────────────┤                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │                   │                   │
    ═════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════
    ══ After 120 seconds, grace timer expires:                                                                                                                              ══
    ═════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════

Timer                EventDispatcher     EnforcementEngine    PolicyManager     ActionExecutor     LifecycleMgr     LockTaskCtrl     DeviceAdminCtrl     EventChannel
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │  grace expired        │                   │                   │                   │                   │                   │                   │                   │
    │  dispatch(            │                   │                   │                   │                   │                   │                   │                   │
    │   GracePeriodExpired) │                   │                   │                   │                   │                   │                   │                   │
    ├──────────────────────►│                   │                   │                   │                   │                   │                   │                   │
    │                       │  submit(event)    │                   │                   │                   │                   │                   │                   │
    │                       ├──────────────────►│                   │                   │                   │                   │                   │                   │
    │                       │                   │  evaluate(state,  │                   │                   │                   │                   │                   │
    │                       │                   │   event)          │                   │                   │                   │                   │                   │
    │                       │                   ├──────────────────►│                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │  [TransitionTo(COUNTDOWN_RUNNING),    │                   │                   │                   │                   │
    │                       │                   │   StartLockCountdown(120000),         │                   │                   │                   │                   │
    │                       │                   │   StartComplianceActivity,            │                   │                   │                   │                   │
    │                       │                   │   ExecuteArmLockTask]                 │                   │                   │                   │                   │
    │                       │                   │◄──────────────────┤                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │  execute(StartComplianceActivity)     │                   │                   │                   │                   │
    │                       │                   ├──────────────────────────────────────────────────────────►│                   │                   │                   │
    │                       │                   │                   │                   │                   │  startActivity   │                   │                   │
    │                       │                   │                   │                   │                   │  (ComplianceAct) │                   │                   │
    │                       │                   │                   │                   │                   │◄──────────────────┤                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │  execute(ExecuteArmLockTask)          │                   │                   │                   │                   │
    │                       │                   ├──────────────────────────────────────────────────────────────────────────────►│                   │                   │
    │                       │                   │                   │                   │                   │                   │  arm()           │                   │
    │                       │                   │                   │                   │                   │                   ├──────────────────►│                   │
    │                       │                   │                   │                   │                   │                   │  setLockTaskPkgs │                   │
    │                       │                   │                   │                   │                   │                   │  setLockTaskFeat │                   │
    │                       │                   │                   │                   │                   │                   │◄──────────────────┤                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
```

**Key point:** The receiver does NOT call `coordinator.evaluateAfterApply()`. It does NOT check if enforcement is armed. It does NOT start a service. It simply emits `LocationProviderChanged`. The engine's `PolicyManager` checks all conditions and decides the full escalation ladder.

---

### 5.3 Boot Completed

```
System              BootCompleted       EventDispatcher     EnforcementEngine    PolicyManager     ActionExecutor     LifecycleMgr     LocationWatchCtrl    WorkScheduler
                       Receiver
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │  BOOT_COMPLETED       │                   │                   │                   │                   │                   │                   │                   │
    ├──────────────────────►│                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │  dispatch(        │                   │                   │                   │                   │                   │                   │
    │                       │   BootCompleted)  │                   │                   │                   │                   │                   │                   │
    │                       ├──────────────────►│                   │                   │                   │                   │                   │                   │
    │                       │                   │  submit(event)    │                   │                   │                   │                   │                   │
    │                       │                   ├──────────────────►│                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  evaluate(state,  │                   │                   │                   │                   │
    │                       │                   │                   │   event)          │                   │                   │                   │                   │
    │                       │                   │                   ├──────────────────►│                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │  // Policy checks:│                   │                   │                   │
    │                       │                   │                   │                   │  // state.enforcementArmed == true?  │                   │                   │                   │
    │                       │                   │                   │                   │  // (restored from persistence)      │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  [StartEnforcementService,            │                   │                   │                   │
    │                       │                   │                   │   StartLocationWatch,                 │                   │                   │                   │
    │                       │                   │                   │   SchedulePeriodicReapply]            │                   │                   │                   │
    │                       │                   │                   │◄──────────────────┤                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  execute(StartEnforcementService)     │                   │                   │                   │
    │                       │                   │                   ├──────────────────────────────────────────────────────────►│                   │                   │
    │                       │                   │                   │                   │                   │  startService()  │                   │                   │
    │                       │                   │                   │                   │                   │◄──────────────────┤                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  execute(StartLocationWatch)          │                   │                   │                   │
    │                       │                   │                   ├──────────────────────────────────────────────────────────────────────────────►│                   │
    │                       │                   │                   │                   │                   │                   │  startWatch()    │                   │
    │                       │                   │                   │                   │                   │                   │  registerReceiver│                   │
    │                       │                   │                   │                   │                   │                   │  (EXPORTED ✓)    │                   │
    │                       │                   │                   │                   │                   │                   │◄──────────────────┤                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  execute(SchedulePeriodicReapply)     │                   │                   │                   │
    │                       │                   │                   ├──────────────────────────────────────────────────────────────────────────────────────────────────────►│
    │                       │                   │                   │                   │                   │                   │                   │  scheduleReapply()
    │                       │                   │                   │                   │                   │                   │                   │  (UPDATE policy)
    │                       │                   │                   │                   │                   │                   │                   │◄──────────────────┤
    │                       │                   │                   │                   │                   │                   │                   │                   │
```

**Key point:** The `BootCompletedReceiver` does NOT check `store.isEnforcementArmed()` and does NOT start a service. It emits `BootCompleted`. The engine's `PolicyManager` checks the armed state (restored from persistence by `StateManager`) and decides to start the service, location watch, and periodic reapply.

---

### 5.4 Service Restarted with Null Intent (START_STICKY Recovery)

```
System              EnforcementFG       EventDispatcher     EnforcementEngine    PolicyManager     StateManager     ActionExecutor     LifecycleMgr
                       Service
    │                       │                   │                   │                   │                   │                   │                   │
    │  restart service      │                   │                   │                   │                   │                   │                   │
    │  (null intent)        │                   │                   │                   │                   │                   │                   │
    ├──────────────────────►│                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │
    │                       │  ServiceGuard     │                   │                   │                   │                   │                   │
    │                       │  .startForeground │                   │                   │                   │                   │                   │
    │                       │  (immediately —   │                   │                   │                   │                   │                   │
    │                       │   crash-safe)     │                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │
    │                       │  dispatch(        │                   │                   │                   │                   │                   │
    │                       │   ServiceRestarted)│                   │                   │                   │                   │                   │
    │                       ├──────────────────►│                   │                   │                   │                   │                   │
    │                       │                   │  submit(event)    │                   │                   │                   │                   │
    │                       │                   ├──────────────────►│                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  evaluate(state,  │                   │                   │                   │
    │                       │                   │                   │   event)          │                   │                   │                   │
    │                       │                   │                   ├──────────────────►│                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │  // Policy checks:│                   │                   │
    │                       │                   │                   │                   │  // What was the state before     │                   │                   │
    │                       │                   │                   │                   │  // process death?                │                   │                   │
    │                       │                   │                   │                   │  // state.complianceState?        │                   │                   │
    │                       │                   │                   │                   │  // state.enforcementArmed?       │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  [StartLocationWatch,  // re-register receiver     │                   │                   │
    │                       │                   │                   │   SchedulePeriodicReapply,  // re-schedule    │                   │                   │
    │                       │                   │                   │   RestoreTimers]  // re-arm grace/countdown       │                   │                   │
    │                       │                   │                   │  // if state was COUNTDOWN_RUNNING:               │                   │                   │
    │                       │                   │                   │  //   recalculate remaining time from deadline    │                   │                   │
    │                       │                   │                   │◄──────────────────┤                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  execute(StartLocationWatch)          │                   │                   │
    │                       │                   │                   ├──────────────────────────────────────────────────────────────────────►│                   │
    │                       │                   │                   │                   │                   │                   │  startWatch()    │
    │                       │                   │                   │                   │                   │                   │  (single instance)│
    │                       │                   │                   │                   │                   │                   │◄──────────────────┤
    │                       │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  execute(RestoreTimers)               │                   │                   │
    │                       │                   │                   ├──────────────────────────────────────────────────────────────────────►│                   │
    │                       │                   │                   │                   │                   │                   │  recalculate     │
    │                       │                   │                   │                   │                   │                   │  remaining time  │
    │                       │                   │                   │                   │                   │                   │  from deadline   │
    │                       │                   │                   │                   │                   │                   │  startTimer(rem) │
    │                       │                   │                   │                   │                   │                   │◄──────────────────┤
    │                       │                   │                   │                   │                   │                   │                   │
```

**Key point:** The service does NOT decide what to do on null intent. It calls `startForeground()` immediately (crash-safe) and emits `ServiceRestarted`. The engine's `PolicyManager` reads the persisted state and decides to re-register the receiver, re-schedule WorkManager, and restore any active timers based on the persisted deadline.

---

### 5.5 User Present / Device Unlock (Consolidated Receiver)

```
System              UserPresent         EventDispatcher     EnforcementEngine    PolicyManager     ActionExecutor     DeviceAdminCtrl     EventChannel
                       Receiver
    │                       │                   │                   │                   │                   │                   │                   │
    │  ACTION_USER_PRESENT  │                   │                   │                   │                   │                   │                   │
    ├──────────────────────►│                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │
    │                       │  dispatch(        │                   │                   │                   │                   │                   │
    │                       │   UserPresent)    │                   │                   │                   │                   │                   │
    │                       ├──────────────────►│                   │                   │                   │                   │                   │
    │                       │                   │  submit(event)    │                   │                   │                   │                   │
    │                       │                   ├──────────────────►│                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  evaluate(state,  │                   │                   │                   │
    │                       │                   │                   │   event)          │                   │                   │                   │
    │                       │                   │                   ├──────────────────►│                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │  // Policy checks:│                   │                   │
    │                       │                   │                   │                   │  // Is lockout active?             │                   │                   │
    │                       │                   │                   │                   │  // state.lockoutActive == true?   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  IF lockout active:                    │                   │                   │
    │                       │                   │                   │  [ExecuteLockNow,                     │                   │                   │
    │                       │                   │                   │   EmitLockResult("locked")]            │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  IF lockout NOT active:                │                   │                   │
    │                       │                   │                   │  // Check compliance state:            │                   │                   │
    │                       │                   │                   │  // If LOCATION_OFF or COUNTDOWN:      │                   │                   │
    │                       │                   │                   │  [ReevaluateLocation]  // trigger      │                   │                   │
    │                       │                   │                   │  //   fresh location check            │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │◄──────────────────┤                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  execute(ExecuteLockNow)  // if lockout│                   │                   │
    │                       │                   │                   ├──────────────────────────────────────────────────────────►│                   │
    │                       │                   │                   │                   │                   │  lockNow()       │                   │
    │                       │                   │                   │                   │                   │  DPM.lockNow()   │                   │
    │                       │                   │                   │                   │                   │◄──────────────────┤                   │
    │                       │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  execute(EmitLockResult)              │                   │                   │
    │                       │                   │                   ├──────────────────────────────────────────────────────────────────────────────►│
    │                       │                   │                   │                   │                   │                   │  emitLockResult  │
    │                       │                   │                   │                   │                   │                   │  (main thread)   │
    │                       │                   │                   │                   │                   │                   │◄──────────────────┤
    │                       │                   │                   │                   │                   │                   │                   │
```

**Key point:** There is now a SINGLE `UserPresentReceiver` (consolidated from `LockoutManager`'s internal receiver and `LocationComplianceUnlockReceiver`). It uses `RECEIVER_EXPORTED` (correct for system broadcasts on Android 13+). It does NOT re-lock directly — it emits `UserPresent`. The engine's `PolicyManager` checks the lockout state and compliance state to decide whether to re-lock, re-evaluate location, or do nothing.

---

### 5.6 Permission Granted (Device Admin Enabled)

```
System              DeviceAdminEvent     EventDispatcher     EnforcementEngine    PolicyManager     StateManager     ActionExecutor     LifecycleMgr     EventChannel
                       Receiver
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │  onEnabled()          │                   │                   │                   │                   │                   │                   │                   │
    ├──────────────────────►│                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │  dispatch(        │                   │                   │                   │                   │                   │                   │
    │                       │   DeviceAdmin     │                   │                   │                   │                   │                   │                   │
    │                       │   Enabled)        │                   │                   │                   │                   │                   │                   │
    │                       ├──────────────────►│                   │                   │                   │                   │                   │                   │
    │                       │                   │  submit(event)    │                   │                   │                   │                   │                   │
    │                       │                   ├──────────────────►│                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  evaluate(state,  │                   │                   │                   │                   │
    │                       │                   │                   │   event)          │                   │                   │                   │                   │
    │                       │                   │                   ├──────────────────►│                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │  // Policy checks:│                   │                   │                   │
    │                       │                   │                   │                   │  // Permission now granted          │                   │                   │
    │                       │                   │                   │                   │  // Arm enforcement                │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  [TransitionTo(permissionGranted=true),│                   │                   │                   │
    │                       │                   │                   │   SetEnforcementArmed(true),          │                   │                   │                   │
    │                       │                   │                   │   StartLocationWatch,                 │                   │                   │                   │
    │                       │                   │                   │   SchedulePeriodicReapply,            │                   │                   │                   │
    │                       │                   │                   │   EmitPermissionChanged(true)]        │                   │                   │                   │
    │                       │                   │                   │◄──────────────────┤                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  execute(TransitionTo)                │                   │                   │                   │
    │                       │                   │                   ├──────────────────────────────────────────►│                   │                   │                   │
    │                       │                   │                   │                   │                   │  persist          │                   │                   │
    │                       │                   │                   │                   │                   │◄──────────────────┤                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  execute(StartLocationWatch)          │                   │                   │                   │
    │                       │                   │                   ├──────────────────────────────────────────────────────────────────────────────►│                   │
    │                       │                   │                   │                   │                   │                   │  startWatch()    │                   │
    │                       │                   │                   │                   │                   │                   │◄──────────────────┤                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  execute(EmitPermissionChanged(true))  │                   │                   │                   │
    │                       │                   │                   ├──────────────────────────────────────────────────────────────────────────────────────────────────────►│
    │                       │                   │                   │                   │                   │                   │                   │  emitPermission  │
    │                       │                   │                   │                   │                   │                   │                   │  Changed(true)   │
    │                       │                   │                   │                   │                   │                   │                   │  (main thread)   │
    │                       │                   │                   │                   │                   │                   │                   │◄──────────────────┤
    │                       │                   │                   │                   │                   │                   │                   │                   │
```

**Key point:** `DeviceAdminEventReceiver.onEnabled()` does NOT call `enforceLocation()` which constructs a controller and calls `apply()`. It emits `DeviceAdminEnabled`. The engine's `PolicyManager` decides to arm enforcement, start location watch, schedule periodic reapply, and notify Flutter.

---

### 5.7 Compliance Screen Dismissed (UI Event)

```
User                LocationCompliance   EventDispatcher     EnforcementEngine    PolicyManager     ActionExecutor     LifecycleMgr     LockTaskCtrl     EventChannel
                       Activity
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │  tap "Turn On         │                   │                   │                   │                   │                   │                   │                   │
    │  Location" button     │                   │                   │                   │                   │                   │                   │                   │
    ├──────────────────────►│                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │  dispatch(        │                   │                   │                   │                   │                   │                   │
    │                       │   ComplianceScreen│                   │                   │                   │                   │                   │                   │
    │                       │   Dismissed)      │                   │                   │                   │                   │                   │                   │
    │                       ├──────────────────►│                   │                   │                   │                   │                   │                   │
    │                       │                   │  submit(event)    │                   │                   │                   │                   │                   │
    │                       │                   ├──────────────────►│                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  evaluate(state,  │                   │                   │                   │                   │
    │                       │                   │                   │   event)          │                   │                   │                   │                   │
    │                       │                   │                   ├──────────────────►│                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │  // Policy checks:│                   │                   │                   │
    │                       │                   │                   │                   │  // Did user turn location on?      │                   │                   │
    │                       │                   │                   │                   │  // Query LocationCtrl.isLocationEnabled()              │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  IF location ON:                       │                   │                   │                   │
    │                       │                   │                   │  [TransitionTo(LOCATION_RESTORED),     │                   │                   │                   │
    │                       │                   │                   │   ExecuteDisarmLockTask,              │                   │                   │                   │
    │                       │                   │                   │   FinishComplianceActivity,           │                   │                   │                   │
    │                       │                   │                   │   CancelAllTimers,                    │                   │                   │                   │
    │                       │                   │                   │   TransitionTo(NORMAL)]               │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  IF location still OFF:                │                   │                   │                   │
    │                       │                   │                   │  [TransitionTo(LOCKED),               │                   │                   │                   │
    │                       │                   │                   │   ExecuteLockNow,                     │                   │                   │                   │
    │                       │                   │                   │   FinishComplianceActivity,           │                   │                   │                   │
    │                       │                   │                   │   EmitLockResult("locked")]           │                   │                   │                   │
    │                       │                   │                   │◄──────────────────┤                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │  execute(FinishComplianceActivity)    │                   │                   │                   │
    │                       │                   │                   ├──────────────────────────────────────────────────────────►│                   │                   │
    │                       │                   │                   │                   │                   │  finishActivity()│                   │                   │
    │                       │                   │                   │                   │                   │◄──────────────────┤                   │                   │
    │                       │◄──────────────────────────────────────────────────────────────────────────────────────────────────┤                   │                   │
    │  activity finishes    │                   │                   │                   │                   │                   │                   │                   │
    │◄──────────────────────┤                   │                   │                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │                   │                   │
```

**Key point:** The activity does NOT call `coordinator.onComplianceScreenDismissed()`. It does NOT transition state. It does NOT finish itself. It emits `ComplianceScreenDismissed`. The engine's `PolicyManager` checks whether location was actually turned on and decides the next state transition (restored → normal, or locked).

---

### 5.8 Periodic Reapply (WorkManager)

```
WorkManager         ReapplyWorker       EventDispatcher     EnforcementEngine    PolicyManager     ActionExecutor     LocationCtrl
    │                       │                   │                   │                   │                   │                   │
    │  trigger (15 min)     │                   │                   │                   │                   │                   │
    ├──────────────────────►│                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │
    │                       │  dispatch(        │                   │                   │                   │                   │
    │                       │   PeriodicReapply │                   │                   │                   │                   │
    │                       │   Triggered)      │                   │                   │                   │                   │
    │                       ├──────────────────►│                   │                   │                   │                   │
    │                       │                   │  submit(event)    │                   │                   │                   │
    │                       │                   ├──────────────────►│                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │
    │                       │                   │                   │  evaluate(state,  │                   │                   │
    │                       │                   │                   │   event)          │                   │                   │
    │                       │                   │                   ├──────────────────►│                   │                   │
    │                       │                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │  // Policy checks:│                   │
    │                       │                   │                   │                   │  // Is enforcement armed?          │                   │
    │                       │                   │                   │                   │  // Is location enabled?           │                   │
    │                       │                   │                   │                   │  // (query LocationCtrl)           │                   │
    │                       │                   │                   │                   │                   │                   │
    │                       │                   │                   │  IF location OFF & armed:             │                   │                   │
    │                       │                   │                   │  [ExecuteForceLocationOn,            │                   │                   │
    │                       │                   │                   │   TransitionTo(LOCATION_OFF),        │                   │                   │
    │                       │                   │                   │   StartGraceTimer]                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │
    │                       │                   │                   │  IF location ON:                      │                   │                   │
    │                       │                   │                   │  []  // nothing to do                │                   │                   │
    │                       │                   │                   │◄──────────────────┤                   │                   │
    │                       │                   │                   │                   │                   │                   │
    │                       │                   │                   │  execute(ExecuteForceLocationOn)      │                   │                   │
    │                       │                   │                   ├──────────────────────────────────────────────────────────────────────►│
    │                       │                   │                   │                   │                   │  forceLocationOn()│
    │                       │                   │                   │                   │                   │  setSecureSetting │
    │                       │                   │                   │                   │                   │◄──────────────────┤
    │                       │                   │                   │                   │                   │                   │
    │  Result.success()     │                   │                   │                   │                   │                   │
    │◄──────────────────────┤                   │                   │                   │                   │                   │
    │                       │                   │                   │                   │                   │                   │
```

**Key point:** `ReapplyWorker` does NOT construct `LocationEnforcementController` and does NOT call `controller.apply()`. It emits `PeriodicReapplyTriggered`. The engine's `PolicyManager` checks the current state and location status, then decides whether to force location on and start the grace timer. The worker and the state machine are now synchronized through the engine.

---

## 6. Event & Action Catalog

### 6.1 EnforcementEvent (Sealed Class)

```
EnforcementEvent
├── System Events
│   ├── LocationProviderChanged         // PROVIDERS_CHANGED broadcast
│   ├── UserPresent                     // ACTION_USER_PRESENT broadcast
│   ├── BootCompleted                   // BOOT_COMPLETED broadcast
│   ├── DeviceAdminEnabled              // DeviceAdminReceiver.onEnabled
│   ├── DeviceAdminDisabled             // DeviceAdminReceiver.onDisabled
│   └── ProfileProvisioningComplete     // DeviceAdminReceiver.onProfileProvisioningComplete
│
├── Timer Events
│   ├── GracePeriodExpired              // Grace countdown reached zero
│   ├── LockCountdownExpired            // Lock countdown reached zero
│   └── TimedLockDurationExpired        // Timed lock duration ended
│
├── Service Lifecycle Events
│   ├── ServiceStarted(action)          // Service started with explicit action
│   ├── ServiceRestarted                // Service restarted with null intent (START_STICKY)
│   └── ServiceStopped                  // Service destroyed
│
├── Worker Events
│   └── PeriodicReapplyTriggered        // WorkManager periodic cycle
│
├── UI Events
│   ├── ComplianceScreenDismissed       // User tapped "turn on location" button
│   └── ComplianceScreenDestroyed       // Activity onDestroy
│
├── Flutter Request Events
│   ├── LockNowRequested                // MethodChannel: lockNow
│   ├── LockForDurationRequested(ms)    // MethodChannel: lockForDuration
│   ├── StartServiceRequested(ms?)      // MethodChannel: startService
│   ├── StopServiceRequested            // MethodChannel: stopService
│   ├── RequestPermissionRequested      // MethodChannel: requestPermission
│   ├── SetUninstallBlockedRequested(b) // MethodChannel: setUninstallBlocked
│   └── OpenLocationSettingsRequested   // MethodChannel: openLocationSettings
│
└── Permission Events
    └── PermissionChanged(granted)      // Permission state changed (from DeviceAdminReceiver)
```

### 6.2 EnforcementAction (Sealed Class)

```
EnforcementAction
├── State Actions
│   ├── TransitionTo(state)             // Change compliance state
│   ├── SetLockoutActive(active, deadline?)  // Change lockout state
│   ├── SetServiceRunning(running, action?)  // Change service state
│   ├── SetPermissionGranted(granted)   // Change permission state
│   ├── SetUninstallBlocked(blocked)    // Change uninstall block state
│   └── SetEnforcementArmed(armed)      // Change armed flag
│
├── Command Actions (→ Controllers)
│   ├── ExecuteLockNow                  // → DeviceAdminController.lockNow()
│   ├── ExecuteLockForDuration(ms)      // → DeviceAdminController.lockForDuration()
│   ├── ExecuteForceLocationOn          // → LocationController.forceLocationOn()
│   ├── ExecuteSetUninstallBlocked(b)   // → UninstallController.setBlocked()
│   ├── ExecuteArmLockTask              // → LockTaskController.arm()
│   ├── ExecuteDisarmLockTask           // → LockTaskController.disarm()
│   ├── ExecuteOpenLocationSettings     // → ProvisioningController.openLocationSettings()
│   └── ExecuteRequestProvisioning      // → ProvisioningController.launchProvisioning()
│
├── Lifecycle Actions (→ LifecycleManager)
│   ├── StartEnforcementService(action) // Start foreground service
│   ├── StopEnforcementService          // Stop foreground service
│   ├── StartComplianceActivity         // Start compliance screen
│   ├── FinishComplianceActivity        // Finish compliance screen
│   ├── StartGraceTimer(ms)             // Start grace countdown
│   ├── StartLockCountdown(ms)          // Start lock countdown
│   ├── StartTimedLock(ms)              // Start timed lock duration
│   ├── CancelAllTimers                 // Cancel all active timers
│   ├── RegisterUserPresentReceiver     // Register USER_PRESENT receiver
│   ├── UnregisterUserPresentReceiver   // Unregister USER_PRESENT receiver
│   ├── StartLocationWatch              // Start realtime location watch
│   ├── StopLocationWatch               // Stop realtime location watch
│   ├── SchedulePeriodicReapply         // Schedule WorkManager periodic
│   └── CancelPeriodicReapply           // Cancel WorkManager periodic
│
└── Notification Actions (→ EventEmitter)
    ├── EmitLockResult(result)           // → EventChannel: lockResult
    ├── EmitServiceStateChanged(status)  // → EventChannel: serviceStateChanged
    ├── EmitLockoutStateChanged(active)  // → EventChannel: lockoutStateChanged
    ├── EmitPermissionChanged(granted)   // → EventChannel: permissionChanged
    └── EmitUninstallBlockChanged(b)     // → EventChannel: uninstallBlockChanged
```

### 6.3 EnforcementState (Unified State)

```
EnforcementState
├── complianceState: ComplianceState     // NORMAL | LOCATION_OFF | COUNTDOWN_RUNNING | COMPLIANCE_SCREEN | LOCKED | LOCATION_RESTORED
├── lockoutActive: Boolean
├── lockoutDeadline: Long?               // elapsedRealtime ms
├── serviceRunning: Boolean
├── serviceAction: ServiceAction?        // NONE | COUNTDOWN | TIMED_LOCK | COMPLIANCE
├── permissionGranted: Boolean
├── uninstallBlocked: Boolean
├── enforcementArmed: Boolean
├── lockIteration: Int
├── countdownDeadline: Long?             // wall-clock ms (for cross-reboot persistence)
└── timedLockDurationMs: Long?           // remaining duration for timed lock
```

### 6.4 EnforcementPolicy (Configuration)

```
EnforcementPolicy
├── gracePeriodMs: Long = 120_000        // 2 minutes
├── lockCountdownMs: Long = 120_000      // 2 minutes
├── lockoutDurationMs: Long = 60_000     // 1 minute
├── lockDelayMs: Long = 30_000           // 30 seconds (service countdown)
├── reapplyIntervalMinutes: Long = 15    // WorkManager minimum
├── compliancePollIntervalMs: Long = 2_000  // Activity poll interval
└── countdownTickIntervalMs: Long = 1_000  // Activity UI refresh
```

---

## 7. Threading Model

### 7.1 Thread Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Thread Architecture                              │
│                                                                     │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐     │
│  │ Binder Thread    │  │ Main Thread     │  │ WorkManager     │     │
│  │ (system callbacks│  │ (Flutter UI,    │  │ Thread Pool     │     │
│  │  for receivers)  │  │  Activity)      │  │                 │     │
│  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘     │
│           │                     │                     │              │
│           │  dispatch(event)    │  dispatch(event)    │  dispatch()  │
│           └─────────────────────┴─────────────────────┘              │
│                                 │                                   │
│                                 ▼                                   │
│                    ┌────────────────────────┐                       │
│                    │  EventDispatcher       │                       │
│                    │  (object singleton)    │                       │
│                    │                        │                       │
│                    │  - SingleThreadExecutor│                       │
│                    │  - Event buffer        │                       │
│                    │                        │                       │
│                    │  dispatch(event):      │                       │
│                    │    executor.execute {  │                       │
│                    │      engine.submit(    │                       │
│                    │        event)          │                       │
│                    │    }                   │                       │
│                    └───────────┬────────────┘                       │
│                                │                                    │
│                                ▼                                    │
│                    ┌────────────────────────┐                       │
│                    │  Engine Thread         │                       │
│                    │  (single-threaded)     │                       │
│                    │                        │                       │
│                    │  1. PolicyManager      │                       │
│                    │     .evaluate()        │                       │
│                    │  2. ActionExecutor     │                       │
│                    │     .execute()         │                       │
│                    │  3. StateManager       │                       │
│                    │     .persist()         │                       │
│                    └───────────┬────────────┘                       │
│                                │                                    │
│                    ┌───────────▼────────────┐                       │
│                    │  ActionExecutor        │                       │
│                    │  routes to:            │                       │
│                    │                        │                       │
│                    │  Controllers →         │                       │
│                    │    execute on engine   │                       │
│                    │    thread (sync)       │                       │
│                    │                        │                       │
│                    │  LifecycleManager →    │                       │
│                    │    execute on engine   │                       │
│                    │    thread (sync)       │                       │
│                    │                        │                       │
│                    │  EventEmitter →        │                       │
│                    │    post to main thread │                       │
│                    │    (Handler.post)      │                       │
│                    └────────────────────────┘                       │
└─────────────────────────────────────────────────────────────────────┘
```

### 7.2 Thread Safety Guarantees

| Component | Thread | Safety Mechanism |
|-----------|--------|-----------------|
| `EventDispatcher.dispatch()` | Any thread | `SingleThreadExecutor` serializes all events |
| `EnforcementEngine.submit()` | Engine thread only | Called only by `EventDispatcher`'s executor |
| `PolicyManager.evaluate()` | Engine thread only | Pure function — no shared mutable state |
| `StateManager` | Engine thread only | Single-threaded access — no synchronization needed |
| `ActionExecutor.execute()` | Engine thread only | Single-threaded access |
| Controllers | Engine thread only | Called only by `ActionExecutor` — no synchronization needed |
| `LifecycleManager` | Engine thread only | Called only by `ActionExecutor` |
| `EventEmitter` (LockEventChannel) | Posts to main thread | `Handler(Looper.getMainLooper()).post { }` |
| `LocationComplianceActivity` | Main thread | Standard Android UI thread |

**Key improvement:** The current code has `@Synchronized` on 20+ methods of `LocationComplianceCoordinator` because multiple threads can access it. The new design eliminates all synchronization needs by serializing all event processing through a single-threaded executor. No `@Synchronized` annotations needed anywhere (except `EventDispatcher.dispatch()` which uses the executor's internal queue).

---

## 8. Mapping: Old → New

### 8.1 Class Migration Map

| Old Class | New Class(es) | Fate |
|-----------|---------------|------|
| `LocationComplianceCoordinator` (635 lines) | `EnforcementEngine` + `StateManager` + `PolicyManager` + `LifecycleManager` | **Dissolved** — split into 4 focused components |
| `LocationComplianceStateStore` | `ComplianceStateStore` | **Renamed** — same responsibility |
| `LocationComplianceState` | `ComplianceState` (in `EnforcementState`) | **Absorbed** into unified state |
| `LocationComplianceActivity` | `LocationComplianceActivity` (in `ui/`) | **Simplified** — no controller construction, no state transitions |
| `LocationComplianceForegroundService` | `EnforcementForegroundService` | **Merged** with `AppLockForegroundService` |
| `LocationComplianceBootReceiver` | `BootCompletedReceiver` | **Simplified** — event-only, no `isEnforcementArmed()` check |
| `LocationComplianceUnlockReceiver` | `UserPresentReceiver` | **Consolidated** with `LockoutManager`'s internal receiver |
| `LocationEnforcementController` | `LocationController` + `LocationWatchController` + `WorkScheduler` | **Split** — commands separated from watch and scheduling |
| `LocationEnforcementWorker` | `ReapplyWorker` | **Simplified** — event-only, no `controller.apply()` |
| `LocationProviderChangeReceiver` | `LocationProviderChangeReceiver` (in `events/`) | **Simplified** — event-only, no `coordinator.evaluateAfterApply()` |
| `AppLockDeviceAdminReceiver` | `DeviceAdminEventReceiver` | **Simplified** — event-only, no `enforceLocation()` |
| `AppLockForegroundService` | `EnforcementForegroundService` | **Merged** with `LocationComplianceForegroundService` |
| `LockCountdownTimer` | `CountdownTimer` | **Fixed** — surgical `cancel()`, callback to engine |
| `LockoutManager` | `StateManager` (state) + `DeviceAdminController` (re-lock) + `UserPresentReceiver` (event) | **Dissolved** — responsibilities distributed |
| `DeviceAdminController` | `DeviceAdminController` (in `controller/`) | **Simplified** — no eventChannel, no emitX methods |
| `UninstallBlockController` | `UninstallController` | **Decoupled** — no `LocationEnforcementController` dependency |
| `LockTaskController` | `LockTaskController` (in `controller/`) | **Moved** — same responsibility, new package |
| `LockEventChannel` | `LockEventChannel` + `EventEmitter` interface | **Improved** — thread-safe emission, interface for testability |
| `LockMethodChannel` | `LockMethodChannel` | **Simplified** — dispatches to engine, not handlers |
| `AppLockHandler` | `AppLockHandler` (in `channel/handler/`) | **Simplified** — creates events, doesn't execute commands |
| `PermissionHandler` | `PermissionHandler` (in `channel/handler/`) | **Simplified** — creates events, doesn't launch intents |
| `UninstallHandler` | `UninstallHandler` (in `channel/handler/`) | **Simplified** — creates events, doesn't call controllers |
| `PermissionEventBus` | — | **Eliminated** — `DeviceAdminEventReceiver` emits directly to `EventDispatcher` |
| `Constants` | `Constants` + `EnforcementPolicy` | **Split** — channel constants stay, policy constants move to `EnforcementPolicy` |
| `ActivityLauncher` | `ActivityLauncher` (in `permission/`) | **Kept** — used by `ProvisioningController` |
| `MainActivity` | `MainActivity` | **Expanded** — composition root for all engine dependencies |

### 8.2 Responsibility Migration Map

| Responsibility | Old Owner | New Owner |
|----------------|-----------|-----------|
| Compliance state transitions | `LocationComplianceCoordinator` | `StateManager` (transition) + `PolicyManager` (decision) |
| Grace period countdown | `LocationComplianceCoordinator` (Handler) | `LifecycleManager` (timer) + `CountdownTimer` (execution) |
| Lock countdown | `LocationComplianceCoordinator` (Handler) | `LifecycleManager` (timer) + `CountdownTimer` (execution) |
| Start/stop compliance activity | `LocationComplianceCoordinator` | `LifecycleManager` |
| Start/stop foreground service | `AppLockHandler` / `LocationComplianceCoordinator` | `LifecycleManager` |
| Register/unregister receivers | `LocationComplianceCoordinator` / `LockoutManager` | `LifecycleManager` |
| Schedule/cancel WorkManager | `LocationEnforcementController` | `LifecycleManager` (decision) + `WorkScheduler` (execution) |
| Force location on | `LocationEnforcementController` | `PolicyManager` (decision) + `LocationController` (execution) |
| Lock now | `DeviceAdminController` (called from handler) | `PolicyManager` (decision) + `DeviceAdminController` (execution) |
| Re-lock on user present | `LockoutManager.onUserPresent()` | `PolicyManager` (decision) + `DeviceAdminController` (execution) |
| Check enforcement armed on boot | `LocationComplianceBootReceiver` | `PolicyManager` (checks state restored by `StateManager`) |
| Enforce location on admin enabled | `AppLockDeviceAdminReceiver.enforceLocation()` | `PolicyManager` (decision) + `LocationController` (execution) |
| Emit lock result to Flutter | `DeviceAdminController.emitLockResult()` | `ActionExecutor` → `EventEmitter` |
| Emit permission changed | `PermissionEventBus` → `LockEventChannel` | `ActionExecutor` → `EventEmitter` |
| Decide uninstall block side-effect | `UninstallBlockController.setBlocked()` | `PolicyManager` (decides if location enforcement should trigger) |
| Service null-intent recovery | (not handled — crash risk) | `PolicyManager` (reads persisted state on `ServiceRestarted`) |
| Worker state sync | (not handled — desync) | `PolicyManager` (evaluates state on `PeriodicReapplyTriggered`) |

### 8.3 Problems Solved

| Problem ID | How EnforcementEngine Solves It |
|------------|----------------------------------|
| P-01 (duplicated controller construction) | Single `LocationController` instance injected into engine — no fresh construction |
| P-02 (RECEIVER_EXPORTED inconsistency) | Single `UserPresentReceiver` with `RECEIVER_EXPORTED` — consolidated |
| P-03 (cancel nukes all callbacks) | `CountdownTimer.cancel()` uses stored `Runnable` reference — surgical |
| P-04 (uninstall owns location enforcement) | `UninstallController` has no location dependency — `PolicyManager` decides side-effects |
| P-05 (635-line God Object) | Split into `StateManager` + `PolicyManager` + `LifecycleManager` + `ActionExecutor` |
| P-06 (dual DeviceAdminController construction) | Single instance injected into engine — no null eventChannel fallback |
| P-07 (LockEventChannel.instance static) | `EventDispatcher` is the only static — buffers events during restart window |
| P-08 (two foreground services) | Single `EnforcementForegroundService` — consolidated |
| P-09 (START_STICKY null-intent crash) | `ServiceGuard.startForeground()` always called — `ServiceRestarted` event triggers recovery |
| P-10 (WorkManager 15-min gap + KEEP) | `WorkScheduler` uses `UPDATE` policy — `PolicyManager` evaluates on each trigger |
| P-11 (worker doesn't sync with coordinator) | Worker emits `PeriodicReapplyTriggered` — engine evaluates and syncs state |
| P-12 (requestPermission misleading return) | Handler creates event — engine decides and returns result asynchronously |
| P-13 (EventChannel multi-thread emission) | All emissions posted to main thread via `EventEmitter` |
| P-14 (geolocator duplicates native) | `LocationController.isLocationEnabled()` exposed via channel — `geolocator` removed |
| P-15 (multiple object singletons) | Only `EventDispatcher` and `Constants` are objects — all others are injected |
| P-16 (activity bypasses coordinator) | Activity queries engine for state, emits events — no controller construction |
| P-17 (receiver leak on restart) | Single `LocationWatchController` instance — `startWatch()` idempotent |
| P-18 (isRunning static drift) | `StateManager.serviceRunning` is the source of truth — persisted |
| P-19 (stringly-typed dispatch) | Sealed class `EnforcementEvent` — type-safe dispatch |
| P-20 (dual-path state sync race) | Single-threaded engine — pull and push are serialized |
| P-21 (wall-clock countdown) | `PolicyManager` recalculates remaining time from deadline on restore |
| P-22 (activity polls every 2s) | Activity can receive state updates from engine via callback (future improvement) |

---

*End of EnforcementEngine Design Document*
