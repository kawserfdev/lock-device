# 🔔 EnforcementForegroundService — Unified Service Architecture

> **Scope:** Refactor the two existing foreground services into a single unified `EnforcementForegroundService`.
> **Constraint:** No code. Architecture design only.
> **Date:** 2026-07-07
> **Parent docs:** [`plans/enforcement-engine-design.md`](plans/enforcement-engine-design.md:1), [`plans/enforcement-state-machine.md`](plans/enforcement-state-machine.md:1)

---

## Table of Contents

1. [Current State: Two Services](#1-current-state-two-services)
2. [Design Goal: One Unified Service](#2-design-goal-one-unified-service)
3. [Responsibilities](#3-responsibilities)
4. [Architecture Diagram](#4-architecture-diagram)
5. [Class Diagram](#5-class-diagram)
6. [Service Lifecycle](#6-service-lifecycle)
7. [Intent Contract](#7-intent-contract)
8. [Notification Management](#8-notification-management)
9. [Timer Scheduling](#9-timer-scheduling)
10. [Event Reception & Dispatch](#10-event-reception--dispatch)
11. [START_STICKY Restart Handling](#11-start_sticky-restart-handling)
12. [Crash-Safe startForeground](#12-crash-safe-startforeground)
13. [Manifest Declaration](#13-manifest-declaration)
14. [Responsibility Migration Map](#14-responsibility-migration-map)
15. [Problems Solved](#15-problems-solved)

---

## 1. Current State: Two Services

The project currently has **two** foreground services with overlapping responsibilities:

### 1.1 [`AppLockForegroundService`](android/app/src/main/kotlin/com/example/app_lock/service/AppLockForegroundService.kt:43)

| Aspect | Detail |
|----------------|-----------------------------------------------------------|
| **Package** | `com.example.app_lock.service` |
| **Lines** | 312 |
| **Purpose** | Runs lock countdown timer, triggers `lockNow()` when `OVERDUE` flag is true, handles timed lock feature |
| **Notification channel** | `Constants.NOTIFICATION_CHANNEL_ID` |
| **Notification ID** | `Constants.FOREGROUND_NOTIFICATION_ID` |
| **Actions** | `ACTION_START`, `ACTION_STOP`, `ACTION_START_TIMED_LOCK` |
| **Business logic inside** | ✅ Evaluates `Constants.OVERDUE` flag, calls `lockEngine.lockNow()`, arms `LockoutManager`, schedules self-stop, clears lockout |
| **Timers owned** | `LockCountdownTimer` (lock delay), self-stop `Runnable` (timed lock) |
| **Static state** | `@Volatile isRunning: Boolean` |
| **Restart policy** | `START_STICKY` |

### 1.2 [`LocationComplianceForegroundService`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceForegroundService.kt:64)

| Aspect | Detail |
|----------------|-----------------------------------------------------------|
| **Package** | `com.example.app_lock.compliance` |
| **Lines** | 166 |
| **Purpose** | Keeps process alive so runtime `LocationProviderChangeReceiver` survives, restores coordinator state |
| **Notification channel** | `app_lock_compliance_channel` |
| **Notification ID** | `1002` |
| **Actions** | *(none — only null-intent restart handling)* |
| **Business logic inside** | ✅ Calls `LocationComplianceCoordinator.restore()`, calls `LocationEnforcementController.startRealtimeWatch()` |
| **Timers owned** | *(none)* |
| **Static state** | `@Volatile isRunning: Boolean` |
| **Restart policy** | `START_STICKY` |

### 1.3 Problems with Two Services

| Problem | Impact |
|----------------|-----------------------------------------------------------|
| **Duplicate notification channels** | Two channels (`app_lock_channel` + `app_lock_compliance_channel`) — confusing for users in notification settings |
| **Duplicate `startForeground` logic** | Both services have identical Android 14+ `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` handling — code duplication |
| **Duplicate `isRunning` flags** | Two separate volatile booleans — callers must check both to know if "the service" is running |
| **Two notification IDs** | `FOREGROUND_NOTIFICATION_ID` (from Constants) and `1002` — risk of ID collision if not coordinated |
| **Business logic in services** | Both services make decisions: `AppLockForegroundService` evaluates `OVERDUE` and calls `lockNow()`; `LocationComplianceForegroundService` calls `restore()` and `startRealtimeWatch()` — violates the Golden Rule |
| **Timer ownership in service** | `AppLockForegroundService` owns `LockCountdownTimer` and self-stop `Runnable` — timers should be managed by `LifecycleManager`, not the service |
| **Independent start/stop** | Two services started/stopped independently — no atomic "start enforcement" / "stop enforcement" operation |
| **Race conditions** | If both services start simultaneously, two notifications appear; if one crashes, the other may keep running with stale state |

---

## 2. Design Goal: One Unified Service

```
┌─────────────────────────────────────────────────────────────────────────┐
│                                                                         │
│   ONE service. ONE notification. ONE process-keepalive mechanism.       │
│                                                                         │
│   EnforcementForegroundService:                                         │
│                                                                         │
│     ✅ Keeps the process alive                                          │
│     ✅ Receives events (via Intent + EventDispatcher)                   │
│     ✅ Schedules timers (delegates to TimerScheduler)                   │
│     ✅ Calls EnforcementEngine (via EventDispatcher)                    │
│     ✅ Maintains notification (single, updatable)                       │
│     ✅ Restarts automatically (START_STICKY)                            │
│                                                                         │
│     ❌ Makes NO business decisions                                      │
│     ❌ Evaluates NO conditions                                          │
│     ❌ Calls NO controllers directly                                    │
│     ❌ Owns NO state machine logic                                      │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.1 The Golden Rule for the Service

> **The service is a lifecycle shell.** It enters foreground, stays alive, dispatches events to the engine, and updates its notification. Every decision — what to lock, when to lock, whether to start a timer — is made by `EnforcementEngine` via `PolicyManager`. The service never asks "should I lock?" — it only says "the engine told me to dispatch this event."

---

## 3. Responsibilities

### 3.1 What the Service DOES

| Responsibility | Description |
|----------------|-----------------------------------------------------------|
| **Keep process alive** | Enters foreground with a persistent notification; OS will not kill the process under normal memory pressure |
| **Receive events** | Accepts Intent-based commands from receivers, controllers, and the engine itself; converts them to `EnforcementEvent` and dispatches to `EventDispatcher` |
| **Schedule timers** | Hosts the `TimerScheduler` implementation; timers fire as events back into the engine (not as direct callbacks) |
| **Call EnforcementEngine** | On `onStartCommand` (including null-intent restart), dispatches `ServiceStarted` or `ServiceRestarted` event to `EventDispatcher` — the engine decides what to do |
| **Maintain notification** | Creates, updates, and removes the single foreground notification; notification text reflects current enforcement state |
| **Restart automatically** | Returns `START_STICKY`; on null-intent restart, dispatches `ServiceRestarted` event so the engine reconciles state |

### 3.2 What the Service DOES NOT Do

| Prohibited action | Who does it instead |
|---------------------------|-----------------------------------------------------------|
| Evaluate `OVERDUE` flag | `PolicyManager` evaluates state + event |
| Call `lockNow()` directly | `ActionExecutor` → `DeviceAdminController` |
| Call `LocationComplianceCoordinator.restore()` | `StateManager.restore()` on `ServiceRestarted` event |
| Call `startRealtimeWatch()` | `LifecycleManager` on `ServiceRestarted` event |
| Arm `LockoutManager` | `ActionExecutor` → `DeviceAdminController` |
| Clear lockout | `ActionExecutor` → `DeviceAdminController` |
| Decide timer durations | `PolicyManager` returns `StartTimer` action with duration |
| Decide when to stop | `PolicyManager` returns `StopService` action |
| Read/write persisted state | `StateManager` |
| Transition state machine | `StateManager` (triggered by `PolicyManager` output) |

### 3.3 Responsibility Boundary Table

| Component | CAN do | CANNOT do |
|----------------|-----------------------------------------------------------|-----------------------------------------------------------|
| `EnforcementForegroundService` | `startForeground()`, `stopForeground()`, `stopSelf()`, create notification, update notification, dispatch event, host timer scheduler | Read/write engine state, call controllers, evaluate conditions, transition state, decide actions |
| `EventDispatcher` | Buffer events, submit to engine thread | Make decisions, execute actions |
| `EnforcementEngine` | Evaluate policy, execute actions, transition state | Direct system I/O (delegates to controllers) |
| `TimerScheduler` | Start/cancel timers, fire events on expiry | Decide durations, decide what happens on expiry |

---

## 4. Architecture Diagram

### 4.1 Service in the System Context

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Android System                                     │
│                                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │ BootReceiver │  │ LocationProv │  │ UserPresent  │  │ WorkManager  │   │
│  │              │  │ ChangeRecv   │  │ Receiver     │  │ Worker       │   │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘   │
│         │                 │                 │                 │            │
│         │   All receivers dispatch events via EventDispatcher (static)     │
│         │                 │                 │                 │            │
│         ▼                 ▼                 ▼                 ▼            │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                      EventDispatcher (object)                        │  │
│  │                   - SingleThreadExecutor                             │  │
│  │                   - Event buffer (for pre-engine-start)              │  │
│  └──────────────────────────────┬───────────────────────────────────────┘  │
│                                 │                                          │
│                                 ▼                                          │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                     EnforcementEngine                                │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐               │  │
│  │  │ PolicyManager│  │ StateManager │  │ActionExecutor│               │  │
│  │  │ (decides)    │  │ (persists)   │  │ (executes)   │               │  │
│  │  └──────────────┘  └──────────────┘  └──────┬───────┘               │  │
│  └───────────────────────────────────────────────┼──────────────────────┘  │
│                                                   │                          │
│                          Actions: StartTimer, CancelTimer,                  │
│                          UpdateNotification, StopService,                   │
│                          StartActivity, ExecuteLock, etc.                   │
│                                                   │                          │
│         ┌─────────────────────────────────────────┼──────────────┐          │
│         │                                         │              │          │
│         ▼                                         ▼              ▼          │
│  ┌──────────────────────┐         ┌──────────────────────┐  ┌──────────┐  │
│  │EnforcementForeground │         │   Controllers        │  │Lifecycle │  │
│  │     Service          │         │ (DeviceAdmin,        │  │Manager   │  │
│  │                      │         │  LocationEnforce,    │  │          │  │
│  │  ✅ startForeground  │         │  UninstallBlock,     │  │ ✅Activity│  │
│  │  ✅ Notification     │         │  LockTask)           │  │ ✅Receiv. │  │
│  │  ✅ TimerScheduler   │         └──────────────────────┘  │ ✅WorkMgr │  │
│  │  ✅ Event dispatch   │                                    └──────────┘  │
│  │  ✅ START_STICKY     │                                                   │
│  └──────────────────────┘                                                   │
│         ▲                                                                   │
│         │  Intent (ACTION_START, ACTION_STOP, ACTION_DISPATCH_EVENT)       │
│         │                                                                   │
│  ┌──────┴───────────────────────────────────────────────────────────────┐  │
│  │  Callers:                                                            │  │
│  │  - LifecycleManager.startService() / stopService()                   │  │
│  │  - BootReceiver (via context.startForegroundService)                 │  │
│  │  - OS (null-intent START_STICKY restart)                             │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Event Flow Through the Service

```
                    ┌─────────────────────┐
                    │  Caller sends Intent │
                    │  (ACTION_DISPATCH_   │
                    │   EVENT + extra)     │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │ Service.onStartCmd  │
                    │  (intent, flags,    │
                    │   startId)          │
                    └──────────┬──────────┘
                               │
                    ┌──────────┴──────────┐
                    │                     │
               intent == null?     intent.action?
                    │                     │
                    ▼                     ▼
            ┌──────────────┐    ┌──────────────────┐
            │ Dispatch     │    │ Parse action:    │
            │ ServiceRest- │    │ START → dispatch │
            │ arted event  │    │   ServiceStarted │
            └──────┬───────┘    │ STOP → dispatch  │
                   │            │   ServiceStopped │
                   │            │ DISPATCH_EVENT → │
                   │            │   parse extra,   │
                   │            │   dispatch event │
                   │            └────────┬─────────┘
                   │                     │
                   └──────────┬──────────┘
                              │
                              ▼
                    ┌─────────────────────┐
                    │  EventDispatcher    │
                    │  .dispatch(event)   │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │ EnforcementEngine   │
                    │ .submit(event)      │
                    │                     │
                    │ PolicyManager       │
                    │ .evaluate()         │
                    │ → List<Action>      │
                    └──────────┬──────────┘
                               │
                    ┌──────────┴──────────┐
                    │                     │
                    ▼                     ▼
          ┌────────────────┐    ┌──────────────────┐
          │ UpdateNotific- │    │ Other actions    │
          │ ation action   │    │ (lock, timer,    │
          │ → Service      │    │ activity, etc.)  │
          │   updates notif│    │ → ActionExecutor │
          └────────────────┘    └──────────────────┘
```

---

## 5. Class Diagram

### 5.1 Service Class Structure

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    EnforcementForegroundService                          │
│                         : Service()                                      │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Companion (object):                                                    │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  const ACTION_START         = "com.example.app_lock.action       │   │
│  │                                   .START_ENFORCEMENT"            │   │
│  │  const ACTION_STOP          = "com.example.app_lock.action       │   │
│  │                                   .STOP_ENFORCEMENT"             │   │
│  │  const ACTION_DISPATCH_EVENT = "com.example.app_lock.action      │   │
│  │                                   .DISPATCH_EVENT"               │   │
│  │  const EXTRA_EVENT_TYPE     = "eventType"                        │   │
│  │  const EXTRA_EVENT_DATA     = "eventData"  // Bundle             │   │
│  │  const NOTIFICATION_ID      = 1001                               │   │
│  │  const CHANNEL_ID           = "app_lock_enforcement_channel"     │   │
│  │                                                                  │   │
│  │  @Volatile var isRunning: Boolean = false  // private set        │   │
│  │                                                                  │   │
│  │  fun start(context: Context)                                     │   │
│  │  fun stop(context: Context)                                      │   │
│  │  fun dispatchEvent(context: Context, event: EnforcementEvent)    │   │
│  │  fun updateNotification(context: Context, text: String)          │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  Fields:                                                                │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  private val timerScheduler: ServiceTimerScheduler               │   │
│  │  private val notificationHelper: NotificationHelper              │   │
│  │  private val mainHandler: Handler  // for notification updates   │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  Methods:                                                               │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  onCreate()                                                      │   │
│  │    → Initialize timerScheduler, notificationHelper               │   │
│  │    → Create notification channel                                 │   │
│  │                                                                  │   │
│  │  onStartCommand(intent, flags, startId): Int                     │   │
│  │    → startForegroundCrashSafe()  // ALWAYS first                 │   │
│  │    → if intent == null: dispatch(ServiceRestarted)               │   │
│  │    → else: parse action, dispatch appropriate event              │   │
│  │    → return START_STICKY                                         │   │
│  │                                                                  │   │
│  │  onBind(intent): IBinder? = null                                 │   │
│  │                                                                  │   │
│  │  onDestroy()                                                     │   │
│  │    → timerScheduler.cancelAll()                                  │   │
│  │    → isRunning = false                                           │   │
│  │                                                                  │   │
│  │  private startForegroundCrashSafe()                              │   │
│  │  private updateNotificationInternal(text: String)                │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Supporting Classes

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      ServiceTimerScheduler                               │
│                   : TimerScheduler (interface)                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Implements the TimerScheduler interface defined by the engine.         │
│  Hosted by the service (lives as long as the service lives).            │
│                                                                         │
│  Fields:                                                                │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  private val handler: Handler  // main looper                    │   │
│  │  private val timers: MutableMap<String, Runnable>               │   │
│  │  private val context: Context  // for dispatching events         │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  Methods:                                                               │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  startTimer(timerId: String, durationMs: Long,                   │   │
│  │            event: EnforcementEvent)                              │   │
│  │    → cancel existing timer with same ID (idempotent)             │   │
│  │    → create Runnable { dispatch(event) }                         │   │
│  │    → handler.postDelayed(runnable, durationMs)                   │   │
│  │    → timers[timerId] = runnable                                  │   │
│  │                                                                  │   │
│  │  cancelTimer(timerId: String)                                    │   │
│  │    → timers[timerId]?.let { handler.removeCallbacks(it) }        │   │
│  │    → timers.remove(timerId)                                      │   │
│  │    // SURGICAL: removeCallbacks(runnable), NOT                   │   │
│  │    // removeCallbacksAndMessages(null)                           │   │
│  │                                                                  │   │
│  │  cancelAllTimers()                                               │   │
│  │    → for each timer: handler.removeCallbacks(runnable)           │   │
│  │    → timers.clear()                                              │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  Key: Timers fire as EVENTS, not callbacks. The Runnable's only job     │
│  is to dispatch the event to EventDispatcher. The engine decides        │
│  what to do with it.                                                    │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                      NotificationHelper                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Encapsulates all notification channel + notification building logic.   │
│  Extracted from both services to eliminate duplication.                 │
│                                                                         │
│  Fields:                                                                │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  private val context: Context                                    │   │
│  │  private val notificationManager: NotificationManager            │   │
│  │  private var currentNotification: Notification? = null           │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  Methods:                                                               │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  createChannel()                                                 │   │
│  │    → Create "app_lock_enforcement_channel" (IMPORTANCE_LOW)      │   │
│  │    → Idempotent (safe to call multiple times)                    │   │
│  │                                                                  │   │
│  │  buildNotification(title: String, text: String): Notification    │   │
│  │    → NotificationCompat.Builder                                  │   │
│  │    → PRIORITY_LOW, ongoing, small icon                           │   │
│  │    → Returns notification (does NOT call startForeground)        │   │
│  │                                                                  │   │
│  │  updateNotification(text: String)                                │   │
│  │    → Build new notification with same ID                         │   │
│  │    → notificationManager.notify(NOTIFICATION_ID, notification)   │   │
│  │    → Called by service when engine emits UpdateNotification      │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.3 Relationship to Engine Interfaces

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      Engine-defined interfaces                           │
│                       (in events/ or engine/)                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  interface TimerScheduler {                                             │
│    fun startTimer(timerId: String, durationMs: Long,                    │
│                   event: EnforcementEvent)                              │
│    fun cancelTimer(timerId: String)                                     │
│    fun cancelAllTimers()                                                │
│  }                                                                      │
│                       ▲                                                 │
│                       │ implements                                      │
│                       │                                                 │
│  ┌────────────────────┴────────────────────────────────────────────┐    │
│  │              ServiceTimerScheduler                               │    │
│  │              (inside EnforcementForegroundService)               │    │
│  └──────────────────────────────────────────────────────────────────┘    │
│                                                                         │
│  interface ServiceController {                                          │
│    fun startService()                                                   │
│    fun stopService()                                                    │
│    fun isServiceRunning(): Boolean                                      │
│  }                                                                      │
│                       ▲                                                 │
│                       │ implements                                      │
│                       │                                                 │
│  ┌────────────────────┴────────────────────────────────────────────┐    │
│  │         ServiceControllerImpl                                    │    │
│  │         (calls EnforcementForegroundService.start/stop)          │    │
│  └──────────────────────────────────────────────────────────────────┘    │
│                                                                         │
│  interface NotificationPoster {                                         │
│    fun updateNotification(text: String)                                 │
│  }                                                                      │
│                       ▲                                                 │
│                       │ implements                                      │
│                       │                                                 │
│  ┌────────────────────┴────────────────────────────────────────────┐    │
│  │         NotificationHelper                                       │    │
│  │         (inside EnforcementForegroundService)                    │    │
│  └──────────────────────────────────────────────────────────────────┘    │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 6. Service Lifecycle

### 6.1 Lifecycle State Machine

```
                    ┌─────────────┐
                    │   CREATED   │
                    │  (onCreate) │
                    └──────┬──────┘
                           │
                           │ Initialize timerScheduler
                           │ Initialize notificationHelper
                           │ Create notification channel
                           │
                           ▼
                    ┌─────────────┐
          ┌────────│   STARTED   │────────┐
          │        │ (onStartCmd)│        │
          │        └──────┬──────┘        │
          │               │               │
          │    ┌──────────┴──────────┐    │
          │    │                     │    │
          │    ▼                     ▼    │
          │  intent == null?   intent != null?
          │    │                     │    │
          │    ▼                     ▼    │
          │  ┌──────────┐    ┌──────────┐│
          │  │ Dispatch  │    │ Parse    ││
          │  │ ServiceRe │    │ action   ││
          │  │ started   │    │ dispatch ││
          │  └─────┬────┘    │ event    ││
          │        │         └────┬─────┘│
          │        │              │      │
          │        └──────┬───────┘      │
          │               │              │
          │               ▼              │
          │     ┌─────────────────┐      │
          │     │  RUNNING        │      │
          │     │  (foreground    │      │
          │     │   notification  │      │
          │     │   visible)      │      │
          │     │                 │      │
          │     │  Timers active  │      │
          │     │  Engine running │      │
          │     └───────┬─────────┘      │
          │             │                │
          │     ┌───────┴────────┐       │
          │     │                │       │
          │     ▼                ▼       │
          │  ACTION_STOP     OS kills    │
          │  received        process     │
          │     │                │       │
          │     ▼                ▼       │
          │  ┌──────────┐  ┌──────────┐ │
          │  │ Dispatch  │  │ (process │ │
          │  │ ServiceSt │  │  dead)   │ │
          │  │ opped     │  │          │ │
          │  └─────┬────┘  └────┬─────┘ │
          │        │            │       │
          │        ▼            │       │
          │  ┌──────────┐       │       │
          │  │  STOPPED │       │       │
          │  │(onDestroy│       │       │
          │  │ stopSelf)│       │       │
          │  └──────────┘       │       │
          │                     │       │
          │                     ▼       │
          │              ┌────────────┐ │
          └──────────────│ OS restarts│ │
                         │ (null intent│ │
                         │  START_STICKY│
                         └─────┬──────┘ │
                               │        │
                               └────────┘
                               (back to STARTED)
```

### 6.2 Lifecycle Methods

| Method | What it does | What it does NOT do |
|----------------|-----------------------------------------------------------|-----------------------------------------------------------|
| `onCreate()` | Initialize `timerScheduler`, `notificationHelper`, create notification channel | Start timers, dispatch events, call engine |
| `onStartCommand(intent, flags, startId)` | 1. `startForegroundCrashSafe()` (ALWAYS first) 2. Parse intent 3. Dispatch event to `EventDispatcher` 4. Return `START_STICKY` | Evaluate state, call controllers, make decisions |
| `onDestroy()` | Cancel all timers, set `isRunning = false` | Dispatch events (service is stopping), call engine |
| `onBind()` | Return `null` (not a bound service) | — |

### 6.3 Critical Ordering: startForeground BEFORE Everything

```
onStartCommand(intent, flags, startId):
    │
    ├── 1. startForegroundCrashSafe()     ← MUST be first (Android 14: 5s deadline)
    │      └── Creates notification
    │      └── Calls startForeground(id, notification, type)
    │      └── Sets isRunning = true
    │
    ├── 2. Parse intent
    │      ├── intent == null → dispatch(ServiceRestarted)
    │      ├── ACTION_START   → dispatch(ServiceStarted)
    │      ├── ACTION_STOP    → dispatch(ServiceStopRequested)
    │      └── ACTION_DISPATCH_EVENT → parse extra, dispatch(event)
    │
    └── 3. return START_STICKY
```

> **Why startForeground is first:** Android 14+ (API 34+) requires `startForeground()` within 5 seconds of `startForegroundService()`. If the service does any work first (parsing, initializing, dispatching), it risks an ANR/crash. The notification is always ready (created in `onCreate`), so `startForegroundCrashSafe()` is a fast, no-I/O call.

---

## 7. Intent Contract

### 7.1 Intent Actions

| Action | Constant | Purpose | Sender |
|----------------|-----------------------------------------------------------|-----------------------------------------------------------|-----------------------------------------------------------|
| `ACTION_START` | `com.example.app_lock.action.START_ENFORCEMENT` | Start the service (enter foreground) | `LifecycleManager`, `BootReceiver` |
| `ACTION_STOP` | `com.example.app_lock.action.STOP_ENFORCEMENT` | Stop the service (exit foreground) | `LifecycleManager` (when engine decides to stop) |
| `ACTION_DISPATCH_EVENT` | `com.example.app_lock.action.DISPATCH_EVENT` | Send an event to the engine through the service | Any component that has a `Context` but not direct `EventDispatcher` access |

### 7.2 Intent Extras

| Extra key | Type | Used with | Description |
|----------------|-----------------------------------------------------------|-----------------------------------------------------------|-----------------------------------------------------------|
| `EXTRA_EVENT_TYPE` | `String` | `ACTION_DISPATCH_EVENT` | The event type name (maps to `EnforcementEvent` subtype) |
| `EXTRA_EVENT_DATA` | `Bundle` | `ACTION_DISPATCH_EVENT` | Event payload (e.g., duration for `LockForDurationRequested`) |

### 7.3 Event Serialization

The service does **not** serialize/deserialize `EnforcementEvent` objects directly (they are sealed classes, not `Parcelable`). Instead:

```
ACTION_DISPATCH_EVENT flow:

  Sender:
    val intent = Intent(context, EnforcementForegroundService::class.java)
    intent.action = ACTION_DISPATCH_EVENT
    intent.putExtra(EXTRA_EVENT_TYPE, "LockForDurationRequested")
    intent.putExtra(EXTRA_EVENT_DATA, bundleOf("durationMs" to 60000))
    context.startService(intent)

  Service.onStartCommand:
    val eventType = intent.getStringExtra(EXTRA_EVENT_TYPE)
    val eventData = intent.getBundleExtra(EXTRA_EVENT_DATA)
    val event = EventFactory.create(eventType, eventData)  // converts to EnforcementEvent
    EventDispatcher.dispatch(event)
```

`EventFactory` is a simple mapping function (in the `events/` package) that converts `(eventType: String, eventData: Bundle)` → `EnforcementEvent`. This keeps the service free of event construction logic.

### 7.4 Static Helper Methods

The companion object provides static helpers so callers don't need to construct Intents manually:

```
companion object:
  fun start(context: Context):
    val intent = Intent(context, EnforcementForegroundService::class.java)
    intent.action = ACTION_START
    ContextCompat.startForegroundService(context, intent)

  fun stop(context: Context):
    val intent = Intent(context, EnforcementForegroundService::class.java)
    intent.action = ACTION_STOP
    context.startService(intent)

  fun dispatchEvent(context: Context, event: EnforcementEvent):
    val intent = Intent(context, EnforcementForegroundService::class.java)
    intent.action = ACTION_DISPATCH_EVENT
    intent.putExtra(EXTRA_EVENT_TYPE, event.typeName)
    intent.putExtra(EXTRA_EVENT_DATA, event.toBundle())
    context.startService(intent)

  fun updateNotification(context: Context, text: String):
    // Only works if service is running; no-op otherwise
    val intent = Intent(context, EnforcementForegroundService::class.java)
    intent.action = ACTION_UPDATE_NOTIFICATION
    intent.putExtra(EXTRA_NOTIFICATION_TEXT, text)
    context.startService(intent)
```

> **Note:** `dispatchEvent` and `updateNotification` use `startService` (not `startForegroundService`) to avoid re-triggering the foreground start flow. The service is already in foreground; `onStartCommand` handles these as pass-through actions.

---

## 8. Notification Management

### 8.1 Single Notification, Dynamic Content

The unified service maintains **one** notification with **one** ID and **one** channel. The notification text is updated dynamically by the engine to reflect the current enforcement state:

| Engine state | Notification title | Notification text |
|----------------|-----------------------------------------------------------|-----------------------------------------------------------|
| `NORMAL` (service running, no enforcement) | "App Lock" | "Protection active" |
| `LOCATION_DISABLED` / `COUNTDOWN_RUNNING` | "App Lock" | "Location off — grace period active" |
| `COMPLIANCE` | "App Lock" | "Location required — please enable location" |
| `LOCK_REQUIRED` / `LOCKED` | "App Lock" | "Device locked — location required" |
| `LOCATION_RESTORED` | "App Lock" | "Location restored — resuming normal" |
| Timed lock active | "App Lock" | "Device locked for X minutes" |

### 8.2 Notification Update Flow

```
Engine evaluates state → PolicyManager returns:
  [UpdateNotification("Location off — grace period active"),
   TransitionTo(COUNTDOWN_RUNNING),
   StartTimer("grace", 120000, GracePeriodExpired)]

ActionExecutor executes UpdateNotification:
  → NotificationPoster.updateNotification(text)
  → NotificationHelper.updateNotification(text)
  → notificationManager.notify(NOTIFICATION_ID, newNotification)
```

### 8.3 Notification Channel

```
Channel ID:    "app_lock_enforcement_channel"
Channel name:  "Enforcement Service"
Importance:    IMPORTANCE_LOW (no sound)
Show badge:    false
Description:   "Keeps the app lock enforcement system running"
```

This replaces both:
- `Constants.NOTIFICATION_CHANNEL_ID` (from `AppLockForegroundService`)
- `app_lock_compliance_channel` (from `LocationComplianceForegroundService`)

### 8.4 Notification ID

```
NOTIFICATION_ID = 1001
```

A single constant. Replaces:
- `Constants.FOREGROUND_NOTIFICATION_ID` (from `AppLockForegroundService`)
- `1002` (from `LocationComplianceForegroundService`)

---

## 9. Timer Scheduling

### 9.1 Timer Ownership Migration

| Timer | Current owner | New owner |
|----------------|-----------------------------------------------------------|-----------------------------------------------------------|
| Lock countdown timer | `AppLockForegroundService` (owns `LockCountdownTimer`) | `ServiceTimerScheduler` (hosted by service) |
| Self-stop runnable (timed lock) | `AppLockForegroundService` (owns `Runnable`) | `ServiceTimerScheduler` (as `TimedLock` timer) |
| Grace timer | `LocationComplianceCoordinator` (owns `Handler.postDelayed`) | `ServiceTimerScheduler` |
| Lock countdown (compliance) | `LocationComplianceCoordinator` (owns `Handler.postDelayed`) | `ServiceTimerScheduler` |

### 9.2 Timer IDs

The engine identifies timers by string ID. The service maps these to `Runnable` references:

| Timer ID | Started by | Duration | Fires event |
|----------------|-----------------------------------------------------------|---------------------------|------------------------------|
| `"grace"` | `LOCATION_DISABLED` entry action | `policy.gracePeriodMs` | `GracePeriodExpired` |
| `"lockCountdown"` | `COMPLIANCE` entry action | `policy.lockCountdownMs` | `LockCountdownExpired` |
| `"timedLock"` | `LOCK_REQUIRED` entry action (if duration set) | `timedLockDurationMs` | `TimedLockExpired` |
| `"serviceSelfStop"` | `NORMAL` entry action (if service should stop) | `policy.lockDelayMs` | `ServiceSelfStop` |

### 9.3 Timer Fire → Event Flow

```
Timer fires (Runnable executes):
    │
    ▼
  EventDispatcher.dispatch(event)
    │
    ▼
  EnforcementEngine.submit(event)
    │
    ▼
  PolicyManager.evaluate(state, event)
    │
    ▼
  ActionExecutor.execute(actions)
    │
    ├── TransitionTo(newState)
    ├── StartTimer("lockCountdown", 120000, LockCountdownExpired)
    │     → ServiceTimerScheduler.startTimer(...)
    ├── CancelTimer("grace")
    │     → ServiceTimerScheduler.cancelTimer("grace")
    └── UpdateNotification("...")
          → NotificationHelper.updateNotification(...)
```

### 9.4 Surgical Cancellation

The `ServiceTimerScheduler.cancelTimer(timerId)` method uses `handler.removeCallbacks(runnable)` — NOT `handler.removeCallbacksAndMessages(null)`:

```
cancelTimer(timerId: String):
    val runnable = timers.remove(timerId)
    if (runnable != null):
        handler.removeCallbacks(runnable)  // surgical — only this timer
```

This fixes P-15 from the architecture analysis (timer cancellation nuking all callbacks).

---

## 10. Event Reception & Dispatch

### 10.1 Events the Service Dispatches

The service itself dispatches these events (it does not receive them — it creates them from intents):

| Event | When dispatched | Engine response |
|----------------|-----------------------------------------------------------|-----------------------------------------------------------|
| `ServiceStarted` | `ACTION_START` received | Engine starts enforcement (if armed) |
| `ServiceRestarted` | Null intent (START_STICKY restart) | Engine reconciles state (see [State Machine §10](plans/enforcement-state-machine.md:1)) |
| `ServiceStopRequested` | `ACTION_STOP` received | Engine decides whether to actually stop (may keep running if enforcement active) |
| *(any event via `ACTION_DISPATCH_EVENT`)* | Pass-through from external component | Engine processes normally |

### 10.2 Events the Service Does NOT Dispatch

The service does **not** dispatch these — they come from receivers directly to `EventDispatcher`:

| Event | Source | Why not through service |
|----------------|-----------------------------------------------------------|-----------------------------------------------------------|
| `LocationProviderChanged` | `LocationProviderChangeReceiver` | Receiver dispatches directly to `EventDispatcher` (static singleton) |
| `UserPresent` | `UserPresentReceiver` | Same — direct to `EventDispatcher` |
| `BootCompleted` | `BootReceiver` | Same |
| `PermissionChanged` | `DeviceAdminReceiver` | Same |
| `ComplianceScreenDismissed` | `LocationComplianceActivity` | Activity dispatches directly to `EventDispatcher` |

> **Rationale:** Receivers and activities have direct access to `EventDispatcher` (it's a static singleton). They don't need to route through the service. The service's `ACTION_DISPATCH_EVENT` is only for components that need to send events but don't have `EventDispatcher` access (rare — mostly Flutter channel handlers that need to trigger native enforcement).

### 10.3 Event Buffering During Restart

When the service restarts (null intent), there's a window where the engine is not yet initialized. `EventDispatcher` buffers events during this window:

```
Service restart timeline:

  T0: OS creates new process, instantiates service
  T1: Service.onCreate() — timerScheduler, notificationHelper initialized
  T2: Service.onStartCommand(null) — startForegroundCrashSafe()
  T3: Service dispatches ServiceRestarted event
  T4: EventDispatcher receives event
       ├── If engine is initialized → submit to engine
       └── If engine is NOT initialized → buffer event
  T5: Engine finishes initialization (StateManager.restore())
  T6: EventDispatcher flushes buffer to engine
  T7: Engine processes ServiceRestarted → reconciles state
```

---

## 11. START_STICKY Restart Handling

### 11.1 What START_STICKY Guarantees

| Guarantee | What it means |
|----------------|-----------------------------------------------------------|
| Service recreated | OS recreates the service after killing the process |
| Null intent | `onStartCommand` is called with `intent == null` |
| No original action | The original `ACTION_START` / `ACTION_STOP` is lost |
| Delayed | Restart may be delayed (seconds to minutes) depending on OS memory pressure |

### 11.2 What START_STICKY Does NOT Guarantee

| Non-guarantee | Impact | Mitigation |
|----------------|-----------------------------------------------------------|-----------------------------------------------------------|
| Immediate restart | Service may be down for minutes | `WorkManager` periodic reapply (15-min fallback) |
| Process survival | Process is killed; only service is recreated | `StateManager.restore()` reads persisted state |
| Receiver survival | Runtime receivers are lost | `LifecycleManager` re-registers on `ServiceRestarted` |
| Timer survival | All timers are lost | `ServiceTimerScheduler` restores from persisted deadlines |
| Engine survival | Engine is gone | `EventDispatcher.start()` re-creates engine |

### 11.3 Null-Intent Restart Flow

```
onStartCommand(intent == null, flags, startId):

  Step 1: startForegroundCrashSafe()
    └── Notification is already created (onCreate ran)
    └── startForeground(NOTIFICATION_ID, notification, type)
    └── isRunning = true

  Step 2: Dispatch ServiceRestarted event
    └── EventDispatcher.dispatch(ServiceRestarted)
        ├── If engine not ready → buffer event
        └── If engine ready → engine.submit(ServiceRestarted)

  Step 3: Engine processes ServiceRestarted
    └── StateManager.restore()
        ├── Read persisted state
        ├── Read persisted deadlines
        └── Build in-memory EnforcementState
    └── PolicyManager.evaluate(state, ServiceRestarted)
        ├── Reconcile state with reality (location status, deadline expiry)
        └── Return recovery actions
    └── ActionExecutor.execute(actions)
        ├── TransitionTo(reconciledState)
        ├── StartTimer (with remaining duration)
        ├── RegisterReceiver (UserPresent, LocationProviderChange)
        ├── SchedulePeriodicReapply
        └── UpdateNotification

  Step 4: return START_STICKY
```

### 11.4 Why the Service Returns START_STICKY (Not START_NOT_STICKY)

| Option | Behavior | Why chosen/not chosen |
|----------------|-----------------------------------------------------------|-----------------------------------------------------------|
| `START_STICKY` | OS recreates service with null intent | ✅ **Chosen** — enforcement must survive process death |
| `START_NOT_STICKY` | OS does not recreate service | ❌ Enforcement would stop permanently after a kill |
| `START_REDELIVER_INTENT` | OS recreates with last intent | ❌ Last intent may be `ACTION_STOP` — would immediately stop |

The service always returns `START_STICKY` — even for `ACTION_STOP`. This is intentional: if the OS kills the service during a stop, the null-intent restart dispatches `ServiceRestarted`, and the engine decides whether to re-start enforcement (based on persisted `enforcementArmed` state). The engine, not the service, decides whether enforcement should be active.

---

## 12. Crash-Safe startForeground

### 12.1 The Problem

Android 14+ (API 34+) requires `startForeground()` within 5 seconds of `startForegroundService()`. If the service takes too long (e.g., doing I/O, initializing heavy objects), the system throws:

```
ForegroundServiceDidNotStartInTimeException
```

The current services call `startForeground()` early in `onStartCommand`, but they do other work first (creating notification channel, building notification). On a slow device or under memory pressure, this can exceed 5 seconds.

### 12.2 The Solution: ServiceGuard Pattern

The unified service uses a `startForegroundCrashSafe()` method that:

1. **Pre-creates the notification in `onCreate()`** — so `onStartCommand` doesn't need to build it
2. **Calls `startForeground()` as the very first thing in `onStartCommand`** — before any parsing, dispatching, or initialization
3. **Wraps `startForeground()` in a try-catch** — if it fails (e.g., foreground service restrictions), the service stops gracefully instead of crashing

```
startForegroundCrashSafe():
    try:
        val notification = notificationHelper.buildNotification(
            title = "App Lock",
            text = "Protection active"
        )
        if (Build.VERSION.SDK_INT >= UPSIDE_DOWN_CAKE):
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else:
            startForeground(NOTIFICATION_ID, notification)
        isRunning = true
    catch (e: Exception):
        Log.e(TAG, "startForeground failed", e)
        stopSelf()  // graceful degradation — service can't run without foreground
```

### 12.3 Notification Pre-Creation

```
onCreate():
    super.onCreate()
    notificationHelper = NotificationHelper(this)
    notificationHelper.createChannel()  // idempotent
    timerScheduler = ServiceTimerScheduler(this, mainHandler)
    // Notification is built lazily in startForegroundCrashSafe()
    // but channel is ready
```

### 12.4 Android 14+ Foreground Service Type

The service is declared with `foregroundServiceType="specialUse"` in the manifest (see §13). On API 34+, `startForeground()` is called with `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE`.

---

## 13. Manifest Declaration

### 13.1 Service Declaration

```xml
<service
    android:name=".service.EnforcementForegroundService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Device policy enforcement for installment locker application" />
</service>
```

### 13.2 Permissions

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### 13.3 What Gets Removed

The following declarations are **removed** from the manifest (services no longer exist):

```xml
<!-- REMOVED -->
<service android:name=".service.AppLockForegroundService" ... />
<service android:name=".compliance.LocationComplianceForegroundService" ... />
```

---

## 14. Responsibility Migration Map

### 14.1 From AppLockForegroundService

| Current responsibility | Current location | New location | How |
|----------------|-----------------------------------------------------------|-----------------------------------------------------------|-----------------------------------------------------------|
| Enter foreground | `startForegroundService()` | `EnforcementForegroundService.startForegroundCrashSafe()` | Same logic, extracted to helper |
| Create notification channel | `createNotificationChannel()` | `NotificationHelper.createChannel()` | Extracted |
| Build notification | `NotificationCompat.Builder(...)` | `NotificationHelper.buildNotification()` | Extracted |
| Start lock countdown timer | `startCountdown()` → `LockCountdownTimer` | `ServiceTimerScheduler.startTimer("lockCountdown", ...)` | Timer fires as event, not callback |
| Evaluate `OVERDUE` flag | `if (Constants.OVERDUE) lockEngine.lockNow()` | `PolicyManager.evaluate(state, LockCountdownExpired)` | **Business logic removed from service** |
| Call `lockNow()` | `lockEngine.lockNow()` | `ActionExecutor` → `DeviceAdminController.lockNow()` | **Service no longer calls controllers** |
| Start timed lock | `startTimedLock(durationMs)` | Engine processes `LockForDurationRequested` event | **Business logic removed from service** |
| Arm `LockoutManager` | `LockoutManager.forceStartLockout(durationMs)` | `ActionExecutor` → `DeviceAdminController` | **Service no longer calls LockoutManager** |
| Schedule self-stop | `scheduleSelfStop(durationMs)` | `ServiceTimerScheduler.startTimer("timedLock", ...)` | Timer fires `TimedLockExpired` event |
| Clear lockout on stop | `LockoutManager.clearLockout()` | `ActionExecutor` on `TimedLockExpired` | **Service no longer clears lockout** |
| Emit service state | `emitServiceState(status)` | `EventEmitter` (via `ActionExecutor`) | **Service no longer emits directly** |
| `isRunning` flag | `@Volatile var isRunning` | `EnforcementForegroundService.isRunning` | Consolidated (one flag, not two) |
| `onDestroy` cleanup | Cancel timer, remove callbacks | `timerScheduler.cancelAll()` | Simplified |

### 14.2 From LocationComplianceForegroundService

| Current responsibility | Current location | New location | How |
|----------------|-----------------------------------------------------------|-----------------------------------------------------------|-----------------------------------------------------------|
| Enter foreground | `startForegroundService()` | `EnforcementForegroundService.startForegroundCrashSafe()` | Same logic, consolidated |
| Create notification channel | `createNotificationChannel()` | `NotificationHelper.createChannel()` | Extracted, consolidated |
| Build notification | `NotificationCompat.Builder(...)` | `NotificationHelper.buildNotification()` | Extracted, consolidated |
| Restore coordinator | `LocationComplianceCoordinator.restore(this)` | `StateManager.restore()` on `ServiceRestarted` event | **Business logic removed from service** |
| Start realtime watch | `LocationEnforcementController(this).startRealtimeWatch()` | `LifecycleManager` on `ServiceRestarted` event | **Service no longer calls controllers** |
| `isRunning` flag | `@Volatile var isRunning` | `EnforcementForegroundService.isRunning` | Consolidated (one flag, not two) |
| `onDestroy` cleanup | Set `isRunning = false` | `timerScheduler.cancelAll()`, `isRunning = false` | Simplified |

### 14.3 Net Code Reduction

| Metric | Before (2 services) | After (1 service) | Reduction |
|----------------|-----------------------------------------------------------|-----------------------------------------------------------|-----------------------------------------------------------|
| Service classes | 2 | 1 | 50% |
| Total lines | 312 + 166 = 478 | ~200 (est.) | ~58% |
| Notification channels | 2 | 1 | 50% |
| Notification IDs | 2 | 1 | 50% |
| `isRunning` flags | 2 | 1 | 50% |
| `startForeground` implementations | 2 | 1 | 50% |
| Business logic in services | ~80 lines | 0 | 100% |

---

## 15. Problems Solved

This unified service design directly addresses problems from [`plans/architecture-analysis.md`](plans/architecture-analysis.md:1):

| Problem | How the unified service solves it |
|----------------|-----------------------------------------------------------|
| **P-03** (Duplicate foreground services) | Two services merged into one `EnforcementForegroundService` |
| **P-06** (Duplicate notification channel creation) | Single `NotificationHelper` with one channel |
| **P-11** (Business logic in services) | Service only dispatches events; all decisions in `PolicyManager` |
| **P-12** (Timer ownership in service) | Timers moved to `ServiceTimerScheduler` (implements `TimerScheduler` interface); timers fire as events, not callbacks |
| **P-15** (Timer cancellation nukes all callbacks) | `ServiceTimerScheduler.cancelTimer()` uses surgical `removeCallbacks(runnable)` |
| **P-17** (No crash-safe startForeground) | `startForegroundCrashSafe()` with try-catch + pre-created notification |
| **P-20** (Two isRunning flags) | Single `EnforcementForegroundService.isRunning` |
| **P-21** (Independent start/stop race conditions) | Single service — atomic start/stop via `LifecycleManager` |

---

## Appendix A: Sequence Diagrams

### A.1 Service Start (ACTION_START)

```
LifecycleManager        EnforcementForegroundService       EventDispatcher        EnforcementEngine
       │                          │                              │                       │
       │ start(context)           │                              │                       │
       │─────────────────────────>│                              │                       │
       │                          │                              │                       │
       │                          │ onStartCommand(ACTION_START) │                       │
       │                          │  1. startForegroundCrashSafe │                       │
       │                          │  2. dispatch(ServiceStarted) │                       │
       │                          │─────────────────────────────>│                       │
       │                          │                              │ submit(ServiceStarted)│
       │                          │                              │──────────────────────>│
       │                          │                              │                       │
       │                          │                              │                       │ PolicyManager
       │                          │                              │                       │ .evaluate()
       │                          │                              │                       │ → actions
       │                          │                              │                       │
       │                          │                              │                       │ ActionExecutor
       │                          │                              │                       │ .execute(actions)
       │                          │                              │                       │
       │                          │  3. return START_STICKY      │                       │
       │                          │<─────────────────────────────│                       │
       │                          │                              │                       │
```

### A.2 Service Restart (Null Intent — START_STICKY)

```
Android OS              EnforcementForegroundService       EventDispatcher        EnforcementEngine
    │                          │                              │                       │
    │ onCreate()               │                              │                       │
    │─────────────────────────>│                              │                       │
    │                          │ init timerScheduler          │                       │
    │                          │ init notificationHelper      │                       │
    │                          │ createChannel()              │                       │
    │                          │                              │                       │
    │ onStartCommand(null)     │                              │                       │
    │─────────────────────────>│                              │                       │
    │                          │  1. startForegroundCrashSafe │                       │
    │                          │  2. dispatch(ServiceRestarted)                       │
    │                          │─────────────────────────────>│                       │
    │                          │                              │ buffer (engine down)  │
    │                          │                              │                       │
    │                          │                              │ start() ──────────────>│
    │                          │                              │                       │ StateManager
    │                          │                              │                       │ .restore()
    │                          │                              │                       │
    │                          │                              │ flush buffer ─────────>│
    │                          │                              │                       │ PolicyManager
    │                          │                              │                       │ .evaluate(state,
    │                          │                              │                       │   ServiceRestarted)
    │                          │                              │                       │ → recovery actions
    │                          │                              │                       │
    │                          │                              │                       │ ActionExecutor
    │                          │                              │                       │ .execute(actions)
    │                          │                              │                       │ ├── TransitionTo
    │                          │                              │                       │ ├── StartTimer
    │                          │                              │                       │ ├── RegisterReceiver
    │                          │                              │                       │ ├── ScheduleReapply
    │                          │                              │                       │ └── UpdateNotification
    │                          │                              │                       │       │
    │                          │  updateNotification(text)    │                       │       │
    │                          │<─────────────────────────────│<──────────────────────┘       │
    │                          │  notificationManager.notify  │                       │
    │                          │                              │                       │
    │                          │  3. return START_STICKY      │                       │
    │<─────────────────────────│                              │                       │
    │                          │                              │                       │
```

### A.3 Timer Fire → Event → Action

```
ServiceTimerScheduler     EventDispatcher     EnforcementEngine      ActionExecutor
       │                       │                      │                      │
       │ Timer fires           │                      │                      │
       │ (Runnable executes)  │                      │                      │
       │ dispatch(GracePeriodExpired)                 │                      │
       │──────────────────────>│                      │                      │
       │                       │ submit(GracePeriodExpired)                  │
       │                       │─────────────────────>│                      │
       │                       │                      │ PolicyManager        │
       │                       │                      │ .evaluate(state,     │
       │                       │                      │   GracePeriodExpired)│
       │                       │                      │ → [TransitionTo      │
       │                       │                      │    (COMPLIANCE),     │
       │                       │                      │    StartTimer        │
       │                       │                      │    ("lockCountdown", │
       │                       │                      │     120000,          │
       │                       │                      │     LockCountdownExpired),│
       │                       │                      │    CancelTimer       │
       │                       │                      │    ("grace"),        │
       │                       │                      │    UpdateNotification│
       │                       │                      │    ("Location        │
       │                       │                      │     required")]      │
       │                       │                      │                      │
       │                       │                      │ execute(actions)     │
       │                       │                      │─────────────────────>│
       │                       │                      │                      │
       │ startTimer("lockCountdown", 120000, ...)     │                      │
       │<────────────────────────────────────────────────────────────────────│
       │                       │                      │                      │
       │ cancelTimer("grace")  │                      │                      │
       │<────────────────────────────────────────────────────────────────────│
       │                       │                      │                      │
       │                       │                      │   updateNotification │
       │                       │                      │   (via NotificationHelper)    │
       │                       │                      │                      │
```

### A.4 Service Stop (ACTION_STOP)

```
LifecycleManager        EnforcementForegroundService       EventDispatcher        EnforcementEngine
       │                          │                              │                       │
       │ stop(context)            │                              │                       │
       │─────────────────────────>│                              │                       │
       │                          │                              │                       │
       │                          │ onStartCommand(ACTION_STOP)  │                       │
       │                          │  1. startForegroundCrashSafe │                       │
       │                          │     (already in foreground)  │                       │
       │                          │  2. dispatch(ServiceStopRequested)                   │
       │                          │─────────────────────────────>│                       │
       │                          │                              │ submit(ServiceStopRequested)
       │                          │                              │──────────────────────>│
       │                          │                              │                       │
       │                          │                              │                       │ PolicyManager
       │                          │                              │                       │ .evaluate(state,
       │                          │                              │                       │   ServiceStopRequested)
       │                          │                              │                       │
       │                          │                              │                       │ If enforcementArmed:
       │                          │                              │                       │   → [] (ignore stop,
       │                          │                              │                       │     keep running)
       │                          │                              │                       │
       │                          │                              │                       │ If !enforcementArmed:
       │                          │                              │                       │   → [StopService]
       │                          │                              │                       │
       │                          │                              │                       │ ActionExecutor
       │                          │                              │                       │ .execute([StopService])
       │                          │                              │                       │
       │                          │  stopForegroundService()     │                       │
       │                          │<─────────────────────────────│<──────────────────────┘
       │                          │  cancelAllTimers()           │
       │                          │  stopForeground(REMOVE)      │
       │                          │  stopSelf()                  │
       │                          │  isRunning = false           │
       │                          │                              │                       │
       │                          │  3. return START_STICKY      │
       │                          │     (even on stop — see §11.4)                      │
       │                          │                              │                       │
```

> **Key insight:** Even `ACTION_STOP` returns `START_STICKY`. The engine decides whether to actually stop. If the engine says "keep running" (enforcement is armed), the service stays alive. If the engine says "stop," the service calls `stopSelf()`. But if the OS kills the service after `stopSelf()`, the null-intent restart dispatches `ServiceRestarted`, and the engine re-evaluates — if enforcement is still armed, it re-starts.

---

## Appendix B: Relationship to Parent Design Documents

| This document | Parent document | Relationship |
|----------------|-----------------------------------------------------------|-----------------------------------------------------------|
| §3 (Responsibilities) | [`enforcement-engine-design.md`](plans/enforcement-engine-design.md:1) §1.2 (Decision Boundary Rules) | Service row in the decision boundary table |
| §5.3 (Engine Interfaces) | [`enforcement-engine-design.md`](plans/enforcement-engine-design.md:1) §2 (Package Structure) | `TimerScheduler`, `ServiceController`, `NotificationPoster` interfaces |
| §9 (Timer Scheduling) | [`enforcement-state-machine.md`](plans/enforcement-state-machine.md:1) §8 (Timeout Handling) | Service hosts the `TimerScheduler` that the state machine's timers use |
| §11 (START_STICKY Restart) | [`enforcement-state-machine.md`](plans/enforcement-state-machine.md:1) §10 (Crash Recovery) | `ServiceRestarted` event triggers the crash recovery procedure |
| §12 (Crash-Safe startForeground) | [`architecture-analysis.md`](plans/architecture-analysis.md:1) P-17 | Fixes the missing crash-safe `startForeground` pattern |
