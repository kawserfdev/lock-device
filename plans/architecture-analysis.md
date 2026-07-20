# 🏗️ Architecture Analysis — App Lock (Flutter + Native Kotlin Device Owner)

> **Scope:** Architecture-only analysis. No code changes. No new features. No functionality changes.
> **Analyst role:** Senior Android Enterprise Architect & Kotlin Refactoring Expert
> **Date:** 2026-07-07

---

## Table of Contents

1. [Deliverable 1 — Current Architecture Diagram](#deliverable-1--current-architecture-diagram)
2. [Deliverable 2 — Dependency Diagram](#deliverable-2--dependency-diagram)
3. [Deliverable 3 — Problems List](#deliverable-3--problems-list)
4. [Deliverable 4 — Improvement Plan](#deliverable-4--improvement-plan)
5. [Deliverable 5 — Migration Strategy](#deliverable-5--migration-strategy)

---

## Deliverable 1 — Current Architecture Diagram

### 1.1 High-Level System Topology

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         FLUTTER (Dart) LAYER                            │
│                                                                         │
│  ┌─────────────────┐  ┌─────────────────┐  ┌────────────────────────┐  │
│  │  Presentation   │  │     Domain      │  │         Data           │  │
│  │  (GetX MVVM)    │──│  (Entities +    │──│  (Datasources + Repos) │  │
│  │                 │  │   UseCases +    │  │                        │  │
│  │ • WelcomeView   │  │   Repo IFs)     │  │ • NativeDataSource     │  │
│  │ • StatusView    │  │                 │  │ • LocalDataSource      │  │
│  │ • Controllers   │  │ • 18 UseCases   │  │   (GetStorage)         │  │
│  │ • Bindings      │  │ • 5 Entities    │  │ • 5 Repo Impls         │  │
│  │ • Routes        │  │ • 5 Repo IFs    │  │ • LocationRepoImpl     │  │
│  │                 │  │                 │  │   (geolocator plugin)  │  │
│  └─────────────────┘  └─────────────────┘  └───────────┬────────────┘  │
│                                                       │               │
│  ┌────────────────────────────────────────────────────┘               │
│  │  Core                                                               │
│  │  • AppMethodChannel ──► MethodChannel("app_lock/methods")          │
│  │  • AppEventChannel  ──► EventChannel("app_lock/events")            │
│  │  • Constants, Errors, Result, Logger, Theme                         │
│  └────────────────────────────────────────────────────────────────────┘
└──────────────────────────────────┬──────────────────────────────────────┘
                                   │
                    Platform Channels (JNI / Platform Thread)
                                   │
┌──────────────────────────────────▼──────────────────────────────────────┐
│                      NATIVE (Kotlin) LAYER                              │
│                                                                         │
│  ┌──────────────────┐  ┌──────────────────┐  ┌───────────────────────┐  │
│  │   Entry Point    │  │    Channels      │  │      Handlers         │  │
│  │  MainActivity    │──│ LockMethodChannel│──│ AppLockHandler        │  │
│  │  (FlutterActivity)│  │ LockEventChannel │  │ PermissionHandler     │  │
│  │  wires channels  │  │  (StreamHandler) │  │ UninstallHandler      │  │
│  └──────────────────┘  └──────────────────┘  └──────────┬────────────┘  │
│                                                          │               │
│  ┌───────────────────────────────────────────────────────┘               │
│  │  Admin / Enforcement Layer                                            │
│  │  ┌────────────────────┐  ┌──────────────────────┐                    │
│  │  │ DeviceAdminController│  │LocationEnforcementCtrl│                   │
│  │  │ (DPM operations)    │  │ (location force +    │                   │
│  │  │ lockNow, lockForDur │  │  WorkManager +       │                   │
│  │  │ user restrictions   │  │  realtime watch)     │                   │
│  │  └─────────┬──────────┘  └──────────┬───────────┘                    │
│  │            │                         │                                │
│  │  ┌─────────▼──────────┐  ┌──────────▼───────────┐                    │
│  │  │UninstallBlockCtrl  │  │LocationEnforcementWkr│                    │
│  │  │(DISALLOW_UNINSTALL)│  │(CoroutineWorker,15m) │                    │
│  │  └────────────────────┘  └──────────────────────┘                    │
│  │  ┌────────────────────────────────────────────────┐                  │
│  │  │ AppLockDeviceAdminReceiver                      │                  │
│  │  │ (DeviceAdminReceiver — system callbacks)        │                  │
│  │  └────────────────────────────────────────────────┘                  │
│  │  ┌────────────────────────────────────────────────┐                  │
│  │  │ LocationProviderChangeReceiver                 │                  │
│  │  │ (runtime receiver — PROVIDERS_CHANGED)         │                  │
│  │  └────────────────────────────────────────────────┘                  │
│  └──────────────────────────────────────────────────────────────────────┘
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────────────┐
│  │  Compliance Subsystem (Location Enforcement State Machine)           │
│  │                                                                      │
│  │  LocationComplianceCoordinator (object singleton, 635 lines)         │
│  │       │                                                              │
│  │       ├──► LocationComplianceStateStore (SharedPreferences)          │
│  │       ├──► LocationEnforcementController (constructed fresh ×4)      │
│  │       ├──► LockTaskController                                        │
│  │       ├──► LocationComplianceActivity (UI screen)                    │
│  │       ├──► LocationComplianceForegroundService (START_STICKY)        │
│  │       ├──► LocationComplianceBootReceiver (BOOT_COMPLETED)           │
│  │       └──► LocationComplianceUnlockReceiver (USER_PRESENT)           │
│  │                                                                      │
│  │  State Machine: NORMAL → LOCATION_OFF → COUNTDOWN_RUNNING →          │
│  │    COMPLIANCE_SCREEN → LOCKED → LOCATION_RESTORED → NORMAL           │
│  └──────────────────────────────────────────────────────────────────────┘
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────────────┐
│  │  Service Layer                                                       │
│  │  ┌──────────────────────┐  ┌──────────────────────┐                  │
│  │  │AppLockForegroundSvc  │  │LockoutManager (object)│                  │
│  │  │(START_STICKY,        │  │(USER_PRESENT receiver,│                  │
│  │  │ specialUse)          │  │ re-lock on unlock)    │                  │
│  │  └──────────┬───────────┘  └──────────┬───────────┘                  │
│  │             │                         │                              │
│  │  ┌──────────▼───────────┐  ┌──────────▼───────────┐                  │
│  │  │LockCountdownTimer    │  │DeviceAdminController  │                  │
│  │  │(Handler.postDelayed) │  │(eventChannel=null)    │                  │
│  │  └──────────────────────┘  └──────────────────────┘                  │
│  └──────────────────────────────────────────────────────────────────────┘
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────────────┐
│  │  Cross-Cutting                                                       │
│  │  • Constants.kt (channel names, method names, event types)           │
│  │  • PermissionEventBus (object singleton, CopyOnWriteArrayList)       │
│  │  • ActivityLauncher (fun interface)                                  │
│  └──────────────────────────────────────────────────────────────────────┘
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Flutter Layer — Clean Architecture Detail

```
┌─────────────────────────────────────────────────────────────────────┐
│  Presentation (lib/presentation/)                                    │
│                                                                     │
│  ┌─── welcome/ ───────────────┐    ┌─── status/ ────────────────┐  │
│  │ WelcomeView (GetX)         │    │ StatusView (GetX)          │  │
│  │ WelcomeController          │    │ StatusController           │  │
│  │ WelcomeBinding (DI)        │    │ StatusBinding (DI)         │  │
│  └────────────┬───────────────┘    └────────────┬───────────────┘  │
│               │                                  │                  │
│  ┌────────────▼──────────────────────────────────▼──────────────┐  │
│  │  Domain (lib/domain/)                                         │  │
│  │                                                               │  │
│  │  UseCases (18):                                               │  │
│  │    lockNow, lockForDuration, startService, stopService,       │  │
│  │    checkPermission, requestPermission, checkProvisioning,     │  │
│  │    setUninstallBlocked, isUninstallBlocked, isUninstallSupp,  │  │
│  │    checkLocationEnabled, openLocationSettings,                │  │
│  │    watchLockResult, watchServiceState, watchPermission,       │  │
│  │    watchLockoutState, watchUninstallBlock, watchLocation      │  │
│  │                                                               │  │
│  │  Entities: AppState, LockResult, PermissionStatus,            │  │
│  │            ServiceStatus                                      │  │
│  │                                                               │  │
│  │  Repository Interfaces (5):                                   │  │
│  │    LockRepository, PermissionRepository,                      │  │
│  │    BackgroundServiceRepository, UninstallRepository,          │  │
│  │    LocationRepository                                         │  │
│  └───────────────────────────┬───────────────────────────────────┘  │
│                              │                                       │
│  ┌───────────────────────────▼───────────────────────────────────┐  │
│  │  Data (lib/data/)                                              │  │
│  │                                                               │  │
│  │  ┌─ NativeDataSource ──────────────────────────────────────┐  │  │
│  │  │  AppMethodChannel.invoke() → MethodChannel              │  │  │
│  │  │  AppEventChannel.stream   → EventChannel                │  │  │
│  │  └─────────────────────────────────────────────────────────┘  │  │
│  │  ┌─ LocalDataSource ───────────────────────────────────────┐  │  │
│  │  │  GetStorage (key/value)                                 │  │  │
│  │  └─────────────────────────────────────────────────────────┘  │  │
│  │                                                               │  │
│  │  Repository Implementations:                                  │  │
│  │    LockRepositoryImpl, PermissionRepositoryImpl,              │  │
│  │    BackgroundServiceRepositoryImpl, UninstallRepositoryImpl,  │  │
│  │    LocationRepositoryImpl (uses geolocator plugin ⚠)         │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.3 Native Layer — Enforcement Flow Detail

```
                        ┌─────────────────────┐
                        │  Flutter UI Button   │
                        │  (e.g. "Lock Now")   │
                        └──────────┬──────────┘
                                   │ MethodChannel call
                                   ▼
                        ┌─────────────────────┐
                        │  LockMethodChannel   │
                        │  .onMethodCall()     │
                        └──────────┬──────────┘
                                   │ dispatch
                                   ▼
                        ┌─────────────────────┐
                        │  AppLockHandler      │
                        │  .lockNow()          │
                        └──────────┬──────────┘
                                   │
                    ┌──────────────┴──────────────┐
                    ▼                              ▼
          ┌──────────────────┐          ┌──────────────────┐
          │ DeviceAdminCtrl   │          │ AppLockFGService  │
          │ .lockNow()        │          │ (if service mode) │
          │ → DPM.lockNow()   │          └────────┬─────────┘
          └────────┬─────────┘                    │
                   │                              ▼
                   │                    ┌──────────────────┐
                   │                    │ LockCountdownTmr │
                   │                    │ (30s postDelayed)│
                   │                    └────────┬─────────┘
                   │                             │
                   │                    ┌────────▼─────────┐
                   │                    │ LockoutManager    │
                   │                    │ .forceStartLockout│
                   │                    │ (USER_PRESENT     │
                   │                    │  re-lock)         │
                   │                    └────────┬─────────┘
                   │                             │
                   ▼                             ▼
          ┌──────────────────────────────────────────────┐
          │  LockEventChannel.emitLockResult()            │
          │  → EventChannel.EventSink.success()           │
          │  → Flutter StatusController stream listener   │
          └──────────────────────────────────────────────┘
```

### 1.4 Compliance State Machine

```
                    ┌──────────────────────────────────────────┐
                    │           LocationComplianceCoordinator    │
                    │           (object singleton)              │
                    └──────────────────┬───────────────────────┘
                                       │
         ┌─────────────────────────────┼─────────────────────────────┐
         │                             │                             │
         ▼                             ▼                             ▼
  ┌──────────────┐            ┌──────────────┐            ┌──────────────┐
  │  NORMAL      │──location──│  LOCATION_OFF│──grace(2m)─│ COUNTDOWN_   │
  │              │   off      │              │            │ RUNNING      │
  └──────┬───────┘            └──────┬───────┘            └──────┬───────┘
         ▲                           │                           │
         │                  location │                    countdown│
         │                   restored│                     expires│
         │                           ▼                           ▼
         │                   ┌──────────────┐            ┌──────────────┐
         │                   │  LOCATION_   │            │ COMPLIANCE_  │
         │                   │  RESTORED    │            │ SCREEN       │
         │                   └──────────────┘            │ (Activity +  │
         │                                               │ LockTask)    │
         │                                               └──────┬───────┘
         │                                                      │
         │                                          lock countdown│
         │                                            expires     │
         │                                                      ▼
         │                                               ┌──────────────┐
         └───────────────────────────────────────────────│  LOCKED      │
                  location restored                      │ (DPM.lockNow)│
                                                         └──────────────┘

  Persistence: LocationComplianceStateStore (SharedPreferences)
    • state (enum name)
    • countdownDeadline (wall-clock ms)
    • lockIteration (int)
    • enforcementArmed (boolean)

  Survival Layers:
    1. LocationProviderChangeReceiver (runtime, PROVIDERS_CHANGED)
    2. LocationEnforcementWorker (WorkManager, 15-min periodic)
    3. LocationComplianceForegroundService (START_STICKY)
    4. LocationComplianceBootReceiver (BOOT_COMPLETED)
    5. LocationComplianceUnlockReceiver (USER_PRESENT)
```

---

## Deliverable 2 — Dependency Diagram

### 2.1 Module Dependency Graph (Native Kotlin)

```
                        ┌─────────────────┐
                        │  MainActivity   │
                        │  (composition   │
                        │   root)         │
                        └──────┬──────────┘
                               │ constructs
                 ┌─────────────┼─────────────────┐
                 │             │                 │
                 ▼             ▼                 ▼
        ┌──────────────┐ ┌──────────┐ ┌────────────────┐
        │LockMethodChnl│ │LockEvent │ │ ActivityLauncher│
        │              │ │Channel   │ │ (fun interface) │
        └──────┬───────┘ └────┬─────┘ └────────────────┘
               │               │
               │ dispatches    │ static .instance
               ▼               │ (process-global)
        ┌──────────────┐       │
        │  Handlers    │       │
        │ (3 handlers) │       │
        └──────┬───────┘       │
               │               │
    ┌──────────┼──────────┐    │
    ▼          ▼          ▼    │
┌────────┐┌────────┐┌────────┐│
│AppLock ││Permissn││Uninstll││
│Handler ││Handler ││Handler ││
└───┬────┘└───┬────┘└───┬────┘│
    │         │         │      │
    │         │         ▼      │
    │         │  ┌──────────────┐
    │         │  │UninstallBlock│
    │         │  │Controller    │──── owns ────► LocationEnforcementController ⚠
    │         │  └──────────────┘                  (lifecycle coupling)
    │         │
    │         ▼
    │  ┌──────────────┐
    │  │PermissionEvt │
    │  │Bus (object)  │
    │  └──────┬───────┘
    │         │ post()
    │         ▼
    │  ┌──────────────────────┐
    │  │AppLockDeviceAdmin    │
    │  │Receiver              │
    │  │(system callbacks)    │
    │  └──────────┬───────────┘
    │             │ enforceLocation()
    │             ▼
    │  ┌──────────────────────┐
    │  │LocationEnforcement   │◄──── constructed fresh in 8+ sites ⚠
    │  │Controller            │
    │  └──────────┬───────────┘
    │             │
    │     ┌───────┼───────────────────┐
    │     ▼       ▼                   ▼
    │  ┌──────┐┌────────────┐┌──────────────────┐
    │  │ DPM  ││LocationEnf ││LocationProvider  │
    │  │ ops  ││Worker(15m) ││ChangeReceiver    │
    │  └──────┘└────────────┘└──────────────────┘
    │
    ▼
┌──────────────────┐     ┌──────────────────────┐
│DeviceAdminCtrl   │◄────│ AppLockForegroundSvc  │
│(eventChannel?    │     │ (constructs w/ null)  │
│ = null fallback) │     └──────────┬───────────┘
└────────┬─────────┘                │
         │                          ▼
         │                 ┌────────────────────┐
         │                 │ LockCountdownTimer  │
         │                 └────────────────────┘
         │                          │
         │                 ┌────────▼───────────┐
         │                 │ LockoutManager      │
         │                 │ (object singleton)  │
         │                 │ owns DeviceAdminCtrl│
         │                 │ + USER_PRESENT recv │
         │                 └────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────────────────┐
│  LocationComplianceCoordinator (object, 635 lines)       │
│  ┌─────────────────────────────────────────────────────┐ │
│  │  Constructs fresh LocationEnforcementController in:  │ │
│  │    • start()          • evaluateInternal()           │ │
│  │    • onUserUnlocked() • escalateToLocked()           │ │
│  │  Also constructs: LockTaskController                 │ │
│  │  Also starts: LocationComplianceActivity             │ │
│  │  Also starts: LocationComplianceForegroundService    │ │
│  └─────────────────────────────────────────────────────┘ │
│                                                          │
│  ┌─────────────────┐  ┌──────────────────────────────┐   │
│  │StateStore (SP)  │  │LocationComplianceActivity     │   │
│  │                 │  │  constructs own EnforceCtrl   │   │
│  └─────────────────┘  │  + LockTaskController         │   │
│                       └──────────────────────────────┘   │
│  ┌─────────────────┐  ┌──────────────────────────────┐   │
│  │BootReceiver     │  │UnlockReceiver                 │   │
│  │(BOOT_COMPLETED) │  │(USER_PRESENT, NOT_EXPORTED ⚠) │   │
│  └─────────────────┘  └──────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────┐ │
│  │LocationComplianceForegroundService                   │ │
│  │  constructs own EnforceCtrl + startRealtimeWatch()   │ │
│  └─────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

### 2.2 Flutter Dependency Graph

```
┌─────────────────────────────────────────────────────────────────┐
│  config/dependency_injection.dart (GetX bindings)               │
│  Registers all repos + usecases + controllers                   │
└────────────────────────────┬────────────────────────────────────┘
                             │
         ┌───────────────────┼───────────────────┐
         ▼                   ▼                   ▼
  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
  │WelcomeBinding│   │StatusBinding │    │  AppMethod   │
  │              │    │              │    │  Channel     │
  │ → Controller │    │ → Controller │    │              │
  │ → Repos      │    │ → Repos      │    └──────┬──────┘
  │ → UseCases   │    │ → UseCases   │           │
  └──────┬───────┘    └──────┬───────┘           │
         │                   │                   │
         ▼                   ▼                   ▼
  ┌──────────────────────────────────────────────────────┐
  │  Domain UseCases (18)                                 │
  │  Each takes a Repository interface                    │
  └──────────────────────────┬───────────────────────────┘
                             │
         ┌───────────────────┼───────────────────┐
         ▼                   ▼                   ▼
  ┌─────────────┐    ┌─────────────┐    ┌─────────────────┐
  │LockRepo     │    │Permission   │    │LocationRepo     │
  │Impl         │    │RepoImpl     │    │Impl             │
  │             │    │             │    │ ⚠ uses          │
  │→ NativeDS   │    │→ NativeDS   │    │   geolocator    │
  └──────┬──────┘    └──────┬──────┘    │   plugin        │
         │                  │           └────┬────────────┘
         │                  │                │
         ▼                  ▼                ▼
  ┌──────────────────────────────────────────────────────┐
  │  NativeDataSource                                     │
  │  → AppMethodChannel.invoke(method, args)             │
  │  → AppEventChannel.stream (listen to events)         │
  └──────────────────────────────────────────────────────┘
```

### 2.3 Coupling Heat Map

| Dependency                          | Type         | Severity | Notes                                              |
|-------------------------------------|--------------|----------|----------------------------------------------------|
| UninstallBlockCtrl → LocationEnfCtrl | Ownership    | 🔴 High  | Uninstall owns location enforcement (lifecycle)   |
| Coordinator → EnforceCtrl (×4 fresh) | Construction | 🔴 High  | 4+ fresh instances, no shared state                |
| LockEventChannel.instance (static)   | Global state | 🔴 High  | Process-global mutable, system components depend   |
| LockoutManager → DeviceAdminCtrl     | Ownership    | 🟡 Med   | Object singleton owns controller (null eventChnl)  |
| AppLockFGService → DeviceAdminCtrl   | Construction | 🟡 Med   | Constructs w/ null eventChannel (static fallback)  |
| Activity → EnforceCtrl + LockTask    | Construction | 🟡 Med   | Bypasses coordinator, constructs own instances     |
| ComplianceFGService → EnforceCtrl    | Construction | 🟡 Med   | Fresh instance each onStartCommand, receiver leak  |
| LocationRepoImpl → geolocator        | Duplication  | 🟡 Med   | Second source of truth for location status         |
| Handlers → concrete controllers      | Direct ref   | 🟢 Low   | No interface abstraction but thin dispatch layer   |
| UseCases → Repository interfaces     | Abstraction  | 🟢 Low   | Properly abstracted, good                           |

---

## Deliverable 3 — Problems List

### P-01 — Duplicated Construction of `LocationEnforcementController` (8+ sites)

**Category:** Duplicated Responsibilities
**Severity:** 🔴 Critical
**Files:**
- [`LocationComplianceCoordinator.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceCoordinator.kt:1) — `start()`, `evaluateInternal()`, `onUserUnlocked()`, `escalateToLocked()`
- [`AppLockDeviceAdminReceiver.kt`](android/app/src/main/kotlin/com/example/app_lock/admin/AppLockDeviceAdminReceiver.kt:1) — `enforceLocation()`
- [`LocationComplianceForegroundService.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceForegroundService.kt:1) — `onStartCommand()`
- [`LocationComplianceActivity.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceActivity.kt:1) — `onCreate()`
- [`LocationEnforcementWorker.kt`](android/app/src/main/kotlin/com/example/app_lock/admin/LocationEnforcementWorker.kt:1) — `doWork()`
- [`UninstallBlockController.kt`](android/app/src/main/kotlin/com/example/app_lock/admin/UninstallBlockController.kt:1) — constructor parameter

**Problem:** `LocationEnforcementController` is instantiated as a fresh local object in at least 8 different call sites. Each instance independently registers/unregisters its own `LocationProviderChangeReceiver`, creates its own WorkManager request, and manages its own realtime watch. There is no shared instance, no lifecycle coordination, and no guarantee that receivers from previous instances are unregistered before new ones register.

**Impact:**
- Multiple `LocationProviderChangeReceiver` instances may be simultaneously registered, each firing `onReceive` for the same broadcast.
- `startRealtimeWatch()` called from `LocationComplianceForegroundService.onStartCommand` creates a new controller each time the service is started/restarted — the previous controller's receiver is never explicitly unregistered.
- WorkManager `enqueueUniquePeriodicWork` with `KEEP` policy means the first instance's request wins, but subsequent instances may have different parameters that are silently ignored.

---

### P-02 — CRITICAL: `RECEIVER_EXPORTED` vs `RECEIVER_NOT_EXPORTED` Inconsistency for `ACTION_USER_PRESENT`

**Category:** BroadcastReceiver Problems
**Severity:** 🔴 Critical
**Files:**
- [`LockoutManager.kt`](android/app/src/main/kotlin/com/example/app_lock/service/LockoutManager.kt:85) — registers `ACTION_USER_PRESENT` with `RECEIVER_EXPORTED`
- [`LocationComplianceUnlockReceiver.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceUnlockReceiver.kt:86) — registers `ACTION_USER_PRESENT` with `RECEIVER_NOT_EXPORTED`

**Problem:** Two different receivers listen for the same system broadcast (`ACTION_USER_PRESENT`) but use contradictory export flags:

- `LockoutManager` uses `ContextCompat.registerReceiver(context, receiver, filter, RECEIVER_EXPORTED)` — correct for system broadcasts on Android 13+ (API 33+).
- `LocationComplianceUnlockReceiver` uses `RECEIVER_NOT_EXPORTED` — **incorrect for a system broadcast**. On Android 13+, `RECEIVER_NOT_EXPORTED` means the receiver will only accept broadcasts from the same app's UID. System broadcasts like `ACTION_USER_PRESENT` are sent by the system (UID 1000), so a `NOT_EXPORTED` receiver **will silently fail to receive them**.

**Impact:**
- On Android 13+ devices, `LocationComplianceUnlockReceiver` will never fire. The compliance state machine's `onUserUnlocked()` callback will never be called after a device unlock.
- This means the compliance system cannot re-evaluate location state after the user unlocks the device — a critical gap in the enforcement chain.
- Meanwhile, `LockoutManager`'s receiver works correctly because it uses `RECEIVER_EXPORTED`.
- The inconsistency suggests the flags were set independently without a shared understanding of which broadcasts require which flag.

---

### P-03 — `LockCountdownTimer.cancel()` Nukes All Handler Callbacks

**Category:** Service Lifecycle Problems
**Severity:** 🟡 Medium
**File:** [`LockCountdownTimer.kt`](android/app/src/main/kotlin/com/example/app_lock/service/LockCountdownTimer.kt:64)

**Problem:** The `cancel()` method calls `handler.removeCallbacksAndMessages(null)`. The `null` token argument means **all pending callbacks and messages** on the handler are removed, not just the countdown timer's own runnable.

**Impact:**
- If the `Handler` instance is shared (e.g., the main looper handler), any other scheduled callbacks — such as `AppLockForegroundService.scheduleSelfStop()`'s `postDelayed` runnable, or `LocationComplianceActivity`'s polling runnables — will be silently cancelled.
- This is a latent bug that manifests as missing callbacks when the timer is cancelled, potentially causing the service to never self-stop or the compliance screen to stop polling.
- Currently the handler is passed in via constructor, so the blast radius depends on the caller. If the caller passes `Handler(Looper.getMainLooper())`, all main-thread callbacks are affected.

---

### P-04 — `UninstallBlockController` Owns `LocationEnforcementController` (Cross-Domain Lifecycle Coupling)

**Category:** Tightly Coupled Modules
**Severity:** 🔴 High
**File:** [`UninstallBlockController.kt`](android/app/src/main/kotlin/com/example/app_lock/admin/UninstallBlockController.kt:36)

**Problem:** `UninstallBlockController` takes a `LocationEnforcementController` as a constructor parameter and calls `controller.apply()` inside `setBlocked()`. This couples the uninstall-blocking domain to the location-enforcement domain.

**Impact:**
- Uninstall blocking and location enforcement are unrelated concerns. A change to location enforcement logic can break uninstall blocking and vice versa.
- The `UninstallBlockController` must be constructed with a `LocationEnforcementController` instance, creating a hard dependency. If location enforcement is not supported (e.g., not a Device Owner), the uninstall controller cannot function.
- Testing `UninstallBlockController` in isolation requires mocking `LocationEnforcementController`, which itself depends on `Context`, `DevicePolicyManager`, `WorkManager`, etc.
- The coupling means that toggling uninstall blocking triggers location enforcement as a side effect — surprising and hard to reason about.

---

### P-05 — `LocationComplianceCoordinator` is a 635-Line God Object

**Category:** Scalability Issues
**Severity:** 🔴 High
**File:** [`LocationComplianceCoordinator.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceCoordinator.kt:76)

**Problem:** `LocationComplianceCoordinator` is an `object` singleton with 635 lines of code. It is responsible for:
- State machine transitions (6 states, 10+ transition methods)
- Constructing `LocationEnforcementController` instances (4+ sites)
- Constructing `LockTaskController` instances
- Starting/stopping `LocationComplianceActivity`
- Starting/stopping `LocationComplianceForegroundService`
- Managing countdown timers via `Handler.postDelayed`
- Persisting state via `LocationComplianceStateStore`
- Registering/unregistering `LocationComplianceUnlockReceiver`
- Reconciliation logic (`evaluateInternal()`)
- Grace period management
- Lock iteration tracking

**Impact:**
- Single point of failure — any bug affects the entire compliance subsystem.
- Impossible to test in isolation — it directly constructs dependencies rather than receiving them.
- Adding a new state or transition requires modifying this single massive file.
- The `@Synchronized` annotation on nearly every method (20+ methods) indicates coarse-grained locking that can cause contention and deadlocks if call chains nest.

---

### P-06 — `DeviceAdminController` Dual Construction Paths (Injected vs Null Fallback)

**Category:** Tightly Coupled Modules
**Severity:** 🟡 Medium
**File:** [`DeviceAdminController.kt`](android/app/src/main/kotlin/com/example/app_lock/admin/DeviceAdminController.kt:33)

**Problem:** `DeviceAdminController` is declared as:
```kotlin
class DeviceAdminController(
    private val context: Context,
    private val eventChannel: LockEventChannel? = null
)
```

The `eventChannel` is nullable with a default of `null`. This creates two construction paths:
1. **Injected path** (from `MainActivity`): `DeviceAdminController(context, eventChannel)` — can emit events.
2. **Null fallback path** (from `AppLockForegroundService`, `LockoutManager`): `DeviceAdminController(context)` or `DeviceAdminController(context = this, eventChannel = null)` — cannot emit events, relies on `LockEventChannel.instance` static fallback.

**Impact:**
- Lock results from the null-fallback path may not reach Flutter if the static `instance` is not set (e.g., if the service starts before `MainActivity.configureFlutterEngine` runs, or after process death).
- The dual path makes it unclear which construction is "correct" — developers may use the wrong one.
- The nullable parameter forces every `emitLockResult()` call to null-check the eventChannel AND fall back to the static instance, adding branching logic.

---

### P-07 — `LockEventChannel.instance` Static Singleton (Process-Global Mutable State)

**Category:** Tightly Coupled Modules
**Severity:** 🟡 Medium
**File:** [`LockEventChannel.kt`](android/app/src/main/kotlin/com/example/app_lock/channels/LockEventChannel.kt:26)

**Problem:** `LockEventChannel` has a `companion object` with `var instance: LockEventChannel?` — a process-global mutable static. System-instantiated components (`AppLockDeviceAdminReceiver`, `LocationProviderChangeReceiver`) that cannot receive constructor injection rely on this static to emit events to Flutter.

**Impact:**
- Race condition: if a system callback fires before `MainActivity.configureFlutterEngine` sets `instance`, events are silently dropped.
- After process death and restart (e.g., `START_STICKY` service restart), `instance` is null until Flutter engine reconfigures — any events emitted during this window are lost.
- The static creates a hidden dependency that is not visible in class signatures — any class can call `LockEventChannel.instance?.emitX()` without it being declared as a dependency.
- Makes unit testing impossible without manipulating static state.

---

### P-08 — Two Separate Foreground Services with Duplicated Patterns

**Category:** Duplicated Responsibilities
**Severity:** 🟡 Medium
**Files:**
- [`AppLockForegroundService.kt`](android/app/src/main/kotlin/com/example/app_lock/service/AppLockForegroundService.kt:43)
- [`LocationComplianceForegroundService.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceForegroundService.kt:64)

**Problem:** Two foreground services exist with nearly identical structural patterns:
- Both use `START_STICKY`
- Both use `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
- Both have `companion object { var isRunning = false }` static flags
- Both have `createNotificationChannel()` with identical channel creation logic
- Both have `startForegroundService()` / `stopForegroundService()` with similar notification building
- Both have distinct notification IDs (1001, 1002) but the same channel ID

**Impact:**
- Code duplication — any fix to notification channel creation or foreground service lifecycle must be applied in two places.
- Two simultaneous foreground services consume more system resources (two notifications, two service connections) than a single consolidated service would.
- The `isRunning` static flags can drift out of sync with actual service state after process death.

---

### P-09 — `START_STICKY` Services Restart with Null Intent

**Category:** Service Lifecycle Problems
**Severity:** 🟡 Medium
**Files:**
- [`AppLockForegroundService.kt`](android/app/src/main/kotlin/com/example/app_lock/service/AppLockForegroundService.kt:118) — `onStartCommand`
- [`LocationComplianceForegroundService.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceForegroundService.kt:100) — `onStartCommand`

**Problem:** Both services return `START_STICKY`. When the system kills and restarts a `START_STICKY` service, it calls `onStartCommand` with a **null intent**. Neither service handles the null-intent case to determine what action to perform on restart.

- `AppLockForegroundService.onStartCommand` checks `intent?.action` — if intent is null, none of the `when` branches match, and the method falls through to `return START_STICKY` without calling `startForeground()`. On Android 12+, a foreground service that doesn't call `startForeground()` within 5 seconds of creation throws `ForegroundServiceDidNotStartInTimeException`.
- `LocationComplianceForegroundService.onStartCommand` calls `startForegroundService()` unconditionally (which is correct for the null case), but then calls `LocationComplianceCoordinator.restore(this)` and `LocationEnforcementController(this).startRealtimeWatch()` — creating yet another fresh controller instance.

**Impact:**
- `AppLockForegroundService` may crash on restart if the system recreates it with a null intent and it doesn't call `startForeground()` in time.
- `LocationComplianceForegroundService` leaks a new `LocationProviderChangeReceiver` on every restart because each `startRealtimeWatch()` registers a new receiver without unregistering the previous one.

---

### P-10 — WorkManager 15-Minute Interval Leaves Detection Gap

**Category:** WorkManager Problems
**Severity:** 🟡 Medium
**File:** [`LocationEnforcementController.kt`](android/app/src/main/kotlin/com/example/app_lock/admin/LocationEnforcementController.kt:277)

**Problem:** `scheduleReapply()` enqueues a `PeriodicWorkRequest` with a 15-minute interval (the minimum allowed by WorkManager). The `ExistingPeriodicWorkPolicy.KEEP` policy means if a request with the same unique name already exists, the new request is discarded.

**Impact:**
- 15 minutes is the minimum WorkManager interval — location can be off for up to 15 minutes before the worker re-evaluates.
- The gap is partially filled by `LocationProviderChangeReceiver` (runtime receiver) and `LocationComplianceForegroundService`, but both die with the process. If the process is killed and the runtime receiver is not yet re-registered, the 15-minute worker is the only safety net.
- `KEEP` policy means if the enforcement parameters change (e.g., grace period duration), the old request with stale parameters persists. The new parameters are silently ignored.
- The worker calls `controller.apply()` but does not update `LocationComplianceCoordinator`'s state machine — the coordinator may be in `NORMAL` state while the worker independently enforces location, causing state desynchronization.

---

### P-11 — `LocationEnforcementWorker` Does Not Sync with `LocationComplianceCoordinator` State

**Category:** WorkManager Problems
**Severity:** 🟡 Medium
**File:** [`LocationEnforcementWorker.kt`](android/app/src/main/kotlin/com/example/app_lock/admin/LocationEnforcementWorker.kt:70)

**Problem:** `LocationEnforcementWorker.doWork()` constructs a fresh `LocationEnforcementController` and calls `controller.apply()`. It does not interact with `LocationComplianceCoordinator` at all — no state read, no state update, no transition notification.

**Impact:**
- The worker can force location on and apply restrictions independently of the compliance state machine. The coordinator may believe the state is `NORMAL` while the worker has already enforced location.
- If the worker forces location on, the `LocationProviderChangeReceiver` may fire `PROVIDERS_CHANGED`, which calls `coordinator.evaluateAfterApply()` — but the coordinator doesn't know the worker triggered it.
- The state machine and the worker are two independent enforcement paths that can diverge, making the system's behavior unpredictable.

---

### P-12 — `PermissionHandler.requestPermission()` Returns Misleading Value

**Category:** MethodChannel Misuse
**Severity:** 🟡 Medium
**File:** [`PermissionHandler.kt`](android/app/src/main/kotlin/com/example/app_lock/channels/handlers/PermissionHandler.kt:140)

**Problem:** `requestPermission()` returns `true` when the provisioning intent (`ACTION_PROVISION_MANAGED_DEVICE`) is successfully dispatched — NOT when the permission is actually granted. The actual grant happens asynchronously via `AppLockDeviceAdminReceiver.onEnabled()` or `onProfileProvisioningComplete()`.

**Impact:**
- Flutter receives `true` from `requestPermission()` and may interpret it as "permission granted," updating the UI prematurely.
- The actual grant notification comes later via the `EventChannel` (`permissionChanged` event). If Flutter doesn't handle the event (e.g., app is backgrounded), the UI stays in the wrong state.
- The dual-confirmation pattern (return `true` for intent dispatch + event for actual grant) is not documented in the method channel contract and is a source of confusion.
- The method deliberately does NOT emit `permissionChanged` on request — so there's a window where Flutter thinks permission was requested successfully but has no confirmation of the actual result.

---

### P-13 — EventChannel Events Emitted from Multiple Threads Without Synchronization

**Category:** MethodChannel Misuse
**Severity:** 🟡 Medium
**File:** [`LockEventChannel.kt`](android/app/src/main/kotlin/com/example/app_lock/channels/LockEventChannel.kt:114)

**Problem:** `LockEventChannel` emits events from multiple threads:
- `emitPermissionChanged()` is called from `PermissionEventBus.post()`, which is called from `AppLockDeviceAdminReceiver` callbacks — these run on a **binder thread**.
- `emitLockResult()`, `emitServiceStateChanged()`, `emitLockoutStateChanged()`, `emitUninstallBlockChanged()` are called from handler methods — these run on the **platform (main) thread**.

`EventChannel.EventSink` is documented as not thread-safe. Events emitted from a background thread without posting to the main thread can cause race conditions or crashes in the Flutter engine.

**Impact:**
- `emitPermissionChanged(granted)` called from a binder thread may crash or corrupt the event sink.
- The `events?.success()` call is not guarded by any synchronization or thread-hopping.
- This is a latent crash that manifests intermittently depending on thread scheduling.

---

### P-14 — Flutter-Side `geolocator` Plugin Duplicates Native Location Detection

**Category:** Flutter Responsibilities That Should Move to Native
**Severity:** 🟡 Medium
**File:** [`location_repository_impl.dart`](lib/data/repositories/location_repository_impl.dart:1)

**Problem:** `LocationRepositoryImpl` uses the `geolocator` Flutter plugin to check location status (`isLocationServiceEnabled()`, `locationServiceStatus` stream, `openLocationSettings()`). Meanwhile, the native side has `LocationEnforcementController.isLocationEnabled()` which checks the same thing via `LocationManager`.

This creates **two independent sources of truth** for location status:
1. Flutter's `geolocator` plugin (queries location status via its own native bridge)
2. Native `LocationEnforcementController` (queries `LocationManager` directly)

**Impact:**
- The two sources can disagree — `geolocator` may report location as enabled while `LocationEnforcementController` reports it as disabled (or vice versa) due to timing differences or different APIs.
- The Flutter UI may show "location on" while the native enforcement system has already entered the `LOCATION_OFF` state.
- `geolocator` adds a third-party dependency and its own native bridge, increasing the app's surface area.
- `openLocationSettings()` via `geolocator` opens the system settings page, but the native side could do this directly via `Settings.ACTION_LOCATION_SOURCE_SETTINGS` without a plugin.

---

### P-15 — Multiple `object` Singletons with Process-Global Mutable State

**Category:** Scalability Issues
**Severity:** 🟡 Medium
**Files:**
- [`LocationComplianceCoordinator.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceCoordinator.kt:76) — `object`, holds `@Volatile state`, `Handler`, `@Volatile countdownDeadline`
- [`LockoutManager.kt`](android/app/src/main/kotlin/com/example/app_lock/service/LockoutManager.kt:49) — `object`, holds `appContext`, `lockoutEngine`, `userPresentReceiver`, `@Volatile lockoutActive`
- [`PermissionEventBus.kt`](android/app/src/main/kotlin/com/example/app_lock/permissions/PermissionEventBus.kt:20) — `object`, holds `CopyOnWriteArrayList<Listener>`
- [`Constants.kt`](android/app/src/main/kotlin/com/example/app_lock/util/Constants.kt:13) — `object`, holds all channel/method/event name strings

**Problem:** Four `object` singletons hold process-global mutable state. They are initialized lazily but their state persists for the process lifetime. They cannot be replaced, mocked, or reset in tests.

**Impact:**
- **Testability:** Unit tests cannot isolate behavior because singletons retain state between tests. No way to inject mock implementations.
- **State leakage:** If a test triggers `LocationComplianceCoordinator.start()`, the singleton's state is modified for all subsequent tests.
- **Initialization order:** `LockoutManager.init(context)` must be called before use, but there's no compile-time guarantee. If a system callback fires before `init()`, `appContext` is null and the method silently returns.
- **Concurrency:** `@Synchronized` on every method of `LocationComplianceCoordinator` is coarse-grained — if method A calls method B (both synchronized on the same object), this is reentrant and fine, but if two different threads call different methods, they block each other unnecessarily.

---

### P-16 — `LocationComplianceActivity` Bypasses Coordinator, Constructs Own Controllers

**Category:** Tightly Coupled Modules
**Severity:** 🟡 Medium
**File:** [`LocationComplianceActivity.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceActivity.kt:74)

**Problem:** `LocationComplianceActivity` constructs its own `LocationEnforcementController` and `LockTaskController` in `onCreate()`, rather than receiving them from `LocationComplianceCoordinator`. It polls location status independently every 2 seconds via its own controller instance.

**Impact:**
- The activity's controller instance is separate from the coordinator's — they may have different states.
- The activity calls `LocationComplianceCoordinator.onComplianceScreenDismissed()` in `onDestroy()`, but the coordinator doesn't know about the activity's internal controller state.
- If the coordinator transitions state while the activity is showing, the activity's polling may conflict with the coordinator's state machine.
- The activity's `LockTaskController` is separate from any controller the coordinator might construct — if the coordinator also arms Lock Task Mode, there could be conflicting arm/disarm calls.

---

### P-17 — `LocationComplianceForegroundService` Leaks Receiver on Restart

**Category:** Service Lifecycle Problems
**Severity:** 🟡 Medium
**File:** [`LocationComplianceForegroundService.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceForegroundService.kt:100)

**Problem:** `onStartCommand` calls `LocationEnforcementController(this).startRealtimeWatch()`. Each call to `startRealtimeWatch()` registers a new `LocationProviderChangeReceiver` via `ContextCompat.registerReceiver()`. There is no corresponding `stopRealtimeWatch()` call in `onDestroy` or before the new registration.

**Impact:**
- Every time the service is started or restarted (including `START_STICKY` restarts), a new receiver is registered without unregistering the old one.
- After N restarts, N receivers are all listening for `PROVIDERS_CHANGED`, each calling `onReceive` — N× the work, N× the battery drain.
- This is a resource leak that worsens over time and with process death/restart cycles.

---

### P-18 — `AppLockForegroundService.isRunning` Static Flag Can Drift

**Category:** Service Lifecycle Problems
**Severity:** 🟢 Low
**File:** [`AppLockForegroundService.kt`](android/app/src/main/kotlin/com/example/app_lock/service/AppLockForegroundService.kt:45)

**Problem:** `companion object { var isRunning = false }` is set to `true` in `onCreate()` and `false` in `onDestroy()`. `AppLockHandler.isServiceRunning()` reads this flag instead of querying `ActivityManager` or `PackageManager` for the actual service state.

**Impact:**
- If the process is killed by the system, `onDestroy()` may not be called, leaving `isRunning = true` when the service is actually dead.
- After process restart, the static is re-initialized to `false` (new process), so the flag is correct — but during the death window, Flutter may query `isServiceRunning()` and get a stale `true`.
- The flag is also used by `AppLockHandler.startService()` to skip starting if already running — if the flag is stale-true, the service won't be started when it should be.

---

### P-19 — Method Channel Dispatch is Stringly-Typed Giant When-Branch

**Category:** MethodChannel Misuse
**Severity:** 🟢 Low
**File:** [`LockMethodChannel.kt`](android/app/src/main/kotlin/com/example/app_lock/channels/LockMethodChannel.kt:40)

**Problem:** `onMethodCall` uses a single `when (call.method)` branch with string comparisons. Method names are defined as constants in `Constants.kt` but the dispatch is not type-safe — a typo in a method name string silently falls through to `result.notImplemented()`.

**Impact:**
- No compile-time checking of method name strings.
- Adding a new method requires editing `Constants.kt` (method name), `LockMethodChannel.kt` (dispatch), the handler class (implementation), and the Flutter `NativeDataSource` (call site) — 4 files, no enforcement of consistency.
- The `when` branch will grow linearly with features, becoming harder to maintain.

---

### P-20 — Dual-Path State Synchronization (Pull + Push) Can Race

**Category:** MethodChannel Misuse
**Severity:** 🟢 Low
**Files:**
- [`status_controller.dart`](lib/presentation/status/controllers/status_controller.dart:1) — pulls state on `onInit()` and `onResume()`
- [`app_event_channel.dart`](lib/core/platform/app_event_channel.dart:1) — pushes events via stream

**Problem:** Flutter uses two synchronization paths:
1. **Pull:** On `onInit()` and `onResume()`, the controller calls usecases to fetch current state (permission, lock result, service state, lockout state, uninstall block, location status).
2. **Push:** The `EventChannel` stream pushes state changes as they happen.

If a push event arrives while a pull is in flight, the pull's result may overwrite the push's newer value.

**Impact:**
- Race condition: pull returns stale data that overwrites a fresh push event.
- The window is small (pull is async, push is stream) but exists, especially on slow method channel responses.
- The dual-path design is defensive (belt-and-suspenders) but creates complexity without clear documentation of which path wins.

---

### P-21 — `LocationComplianceStateStore` Uses Wall-Clock for Countdown Deadline

**Category:** Service Lifecycle Problems
**Severity:** 🟢 Low
**File:** [`LocationComplianceStateStore.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceStateStore.kt:1)

**Problem:** The countdown deadline is persisted as `System.currentTimeMillis()` (wall-clock). When the coordinator restores state after process death, it compares `System.currentTimeMillis()` against the persisted deadline.

**Impact:**
- If the user changes the device clock (manual time change, timezone change, NTP sync), the countdown calculation is incorrect — it may expire immediately or never expire.
- `SystemClock.elapsedRealtime()` (monotonic) would be more appropriate for elapsed-time calculations, but it cannot be persisted across process death (it resets on reboot).
- The wall-clock approach is a pragmatic choice for cross-reboot persistence, but the clock-change edge case is unhandled.
- Note: `LockoutManager` correctly uses `SystemClock.elapsedRealtime()` for its in-memory lockout tracking — but this means the two systems use different time bases for similar countdown logic.

---

### P-22 — `LocationComplianceActivity` Polls Every 2 Seconds Instead of Using Receiver

**Category:** Service Lifecycle Problems
**Severity:** 🟢 Low
**File:** [`LocationComplianceActivity.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceActivity.kt:112)

**Problem:** `LocationComplianceActivity` polls location status every 2 seconds (`POLL_INTERVAL_MS = 2000L`) via `locationPollRunnable`. It also refreshes countdown text every 1 second via `countdownTickRunnable`. Both use `Handler.postDelayed` on the main looper.

**Impact:**
- Polling every 2 seconds wastes battery — the native side already has `LocationProviderChangeReceiver` for real-time notification.
- The activity could register its own `PROVIDERS_CHANGED` receiver (or receive callbacks from the coordinator) instead of polling.
- Two runnables on the main looper add to main-thread load, though the impact is minimal.
- The polling continues even when the screen is off (though `showWhenLocked` + `turnScreenOn` means the screen is typically on during compliance).

---

## Deliverable 4 — Improvement Plan

### 4.1 Priority Matrix

| ID  | Problem | Severity | Effort | Priority |
|-----|---------|----------|--------|----------|
| P-02 | RECEIVER_EXPORTED vs NOT_EXPORTED inconsistency | 🔴 Critical | Small | **P0 — Immediate** |
| P-01 | LocationEnforcementController duplicated construction | 🔴 Critical | Large | **P1 — High** |
| P-04 | UninstallBlockController owns LocationEnforcementController | 🔴 High | Medium | **P1 — High** |
| P-05 | LocationComplianceCoordinator God Object (635 lines) | 🔴 High | Large | **P1 — High** |
| P-09 | START_STICKY null-intent crash risk | 🟡 Medium | Small | **P2 — Medium** |
| P-17 | Compliance FG service receiver leak on restart | 🟡 Medium | Small | **P2 — Medium** |
| P-13 | EventChannel multi-thread emission | 🟡 Medium | Small | **P2 — Medium** |
| P-11 | Worker doesn't sync with coordinator state | 🟡 Medium | Medium | **P2 — Medium** |
| P-10 | WorkManager 15-min gap + KEEP policy | 🟡 Medium | Medium | **P2 — Medium** |
| P-06 | DeviceAdminController dual construction | 🟡 Medium | Medium | **P2 — Medium** |
| P-07 | LockEventChannel.instance static singleton | 🟡 Medium | Medium | **P2 — Medium** |
| P-08 | Two foreground services duplicated | 🟡 Medium | Large | **P3 — Low** |
| P-14 | geolocator duplicates native location detection | 🟡 Medium | Medium | **P3 — Low** |
| P-15 | Multiple object singletons | 🟡 Medium | Large | **P3 — Low** |
| P-16 | Activity bypasses coordinator | 🟡 Medium | Medium | **P3 — Low** |
| P-12 | requestPermission() misleading return | 🟡 Medium | Small | **P3 — Low** |
| P-03 | LockCountdownTimer.cancel() nukes all callbacks | 🟡 Medium | Small | **P3 — Low** |
| P-18 | isRunning static flag drift | 🟢 Low | Small | **P4 — Backlog** |
| P-19 | Stringly-typed method channel dispatch | 🟢 Low | Medium | **P4 — Backlog** |
| P-20 | Dual-path state sync race | 🟢 Low | Medium | **P4 — Backlog** |
| P-21 | Wall-clock countdown deadline | 🟢 Low | Small | **P4 — Backlog** |
| P-22 | Activity polls every 2s | 🟢 Low | Small | **P4 — Backlog** |

### 4.2 Improvement Directions (Architecture-Level, No Code)

#### Direction A: Introduce a Shared `LocationEnforcementController` Instance (Addresses P-01, P-17)

**Current:** `LocationEnforcementController` is constructed fresh in 8+ sites. Each instance registers its own receiver, creating leaks and duplicate broadcasts.

**Target:** A single `LocationEnforcementController` instance, owned by a lifecycle-aware component (e.g., `Application` or a dedicated `EnforcementScope`), injected into all consumers. The controller's `startRealtimeWatch()` / `stopRealtimeWatch()` are called once, not per-service-start.

**Benefit:** Eliminates receiver leaks, ensures single source of truth for location status, reduces object creation.

#### Direction B: Decouple `UninstallBlockController` from `LocationEnforcementController` (Addresses P-04)

**Current:** `UninstallBlockController` takes `LocationEnforcementController` as a constructor parameter and calls `controller.apply()` inside `setBlocked()`.

**Target:** Remove the `LocationEnforcementController` dependency from `UninstallBlockController`. If location enforcement needs to be triggered when uninstall blocking is toggled, use an event/callback mechanism (e.g., a `OnUninstallBlockChangedListener` that the coordinator registers) rather than direct ownership.

**Benefit:** Uninstall blocking becomes independently testable. The two domains can evolve independently.

#### Direction C: Split `LocationComplianceCoordinator` into Focused Components (Addresses P-05)

**Current:** 635-line God Object with 20+ synchronized methods.

**Target:** Split into:
- `ComplianceStateMachine` — pure state transitions, no I/O
- `ComplianceTimerService` — countdown/grace period management
- `ComplianceScreenManager` — activity start/stop
- `ComplianceServiceManager` — foreground service start/stop
- `ComplianceReceiverManager` — unlock receiver register/unregister
- `ComplianceCoordinator` — thin orchestrator that delegates to the above

**Benefit:** Each component is independently testable. The state machine is pure logic with no Android dependencies. Changes to one concern don't require touching the others.

#### Direction D: Fix `RECEIVER_EXPORTED` Flag for `LocationComplianceUnlockReceiver` (Addresses P-02)

**Current:** `LocationComplianceUnlockReceiver` uses `RECEIVER_NOT_EXPORTED` for `ACTION_USER_PRESENT` — a system broadcast. This silently fails on Android 13+.

**Target:** Change to `RECEIVER_EXPORTED` (matching `LockoutManager`'s correct usage). System broadcasts require `RECEIVER_EXPORTED` on Android 13+ because they are sent by the system process (UID 1000), not the app's own UID.

**Benefit:** Compliance state machine re-evaluates after device unlock on Android 13+.

#### Direction E: Consolidate Foreground Services (Addresses P-08, P-09, P-17)

**Current:** Two foreground services with duplicated patterns, both `START_STICKY`, both with null-intent issues.

**Target:** A single `EnforcementForegroundService` that handles both lock and compliance enforcement. The service reads its required action from a persisted state (SharedPreferences) rather than the intent, so null-intent restarts can recover correctly. `onStartCommand` always calls `startForeground()` immediately, then reads persisted state to determine what to do.

**Benefit:** Eliminates duplicate notification channel creation, reduces resource usage, fixes null-intent crash risk, eliminates receiver leak.

#### Direction F: Thread-Safe EventChannel Emission (Addresses P-13)

**Current:** `LockEventChannel` emits events from both binder threads and the main thread without synchronization.

**Target:** All `emitX()` methods post to the main thread via `Handler(Looper.getMainLooper()).post { events?.success(...) }` before calling `EventSink.success()`. This ensures all events are emitted from a single thread.

**Benefit:** Eliminates race condition and potential crash in the Flutter engine's event sink.

#### Direction G: Replace `geolocator` with Native Channel (Addresses P-14)

**Current:** Flutter uses `geolocator` plugin for location status, duplicating native `LocationEnforcementController.isLocationEnabled()`.

**Target:** Add `isLocationEnabled` and `openLocationSettings` methods to the existing `app_lock/methods` MethodChannel. Remove `geolocator` dependency from `pubspec.yaml`. `LocationRepositoryImpl` calls the native channel instead of the plugin.

**Benefit:** Single source of truth for location status. Removes third-party dependency. Reduces app size.

#### Direction H: Introduce Interface Abstractions for Native Controllers (Addresses P-15, P-19)

**Current:** All controllers are concrete classes. Handlers reference concrete types. No mocking possible.

**Target:** Define interfaces (`interface DeviceAdminOperations`, `interface LocationEnforcementOperations`, `interface UninstallBlockOperations`). Handlers reference interfaces. `MainActivity` provides concrete implementations via constructor injection. Replace `object` singletons with injected instances where possible.

**Benefit:** Testability (mock interfaces in tests). Decoupling (handlers don't depend on concrete implementations). Clearer contracts (interfaces document what each controller does).

#### Direction I: Worker-Coordinator State Sync (Addresses P-10, P-11)

**Current:** `LocationEnforcementWorker` calls `controller.apply()` independently, without syncing with `LocationComplianceCoordinator`.

**Target:** The worker calls `LocationComplianceCoordinator.evaluateAfterApply()` (or a new `evaluateFromWorker()` method) after `controller.apply()`. The coordinator updates its state machine based on the worker's result. Use `ExistingPeriodicWorkPolicy.UPDATE` instead of `KEEP` so parameter changes take effect.

**Benefit:** State machine and worker are synchronized. Parameter changes are respected.

#### Direction J: Replace Static `LockEventChannel.instance` with Lifecycle-Aware Holder (Addresses P-06, P-07)

**Current:** `LockEventChannel.instance` is a process-global mutable static. System components use it as a fallback when they can't receive constructor injection.

**Target:** A `EventEmitterHolder` singleton (still an `object`, but with a clear single responsibility) that system components call. `LockEventChannel` delegates to this holder. The holder buffers events when no sink is attached and flushes them when the sink connects. Alternatively, use a `LiveData` or `StateFlow` that the channel observes.

**Benefit:** Events are not lost during the window between process restart and Flutter engine reconfiguration. Clearer separation of concerns.

---

## Deliverable 5 — Migration Strategy

### 5.1 Guiding Principles

1. **No functionality changes** — every refactor must preserve existing behavior.
2. **Incremental migration** — each phase is independently shippable and testable.
3. **Test before refactor** — establish behavioral tests (manual or automated) before changing code.
4. **One concern per phase** — don't mix refactors (e.g., don't split the coordinator and fix the receiver flag in the same PR).

### 5.2 Phase Plan

#### Phase 0 — Baseline Verification (Pre-Refactor)

**Goal:** Document current behavior so refactors can be verified.

**Steps:**
1. Manually test all user flows: permission provisioning, lock now, lock for duration, start/stop service, uninstall blocking, location compliance (location off → countdown → compliance screen → locked → restored).
2. Test on Android 13+ (API 33+) specifically for receiver behavior.
3. Test process death scenarios: kill app from adb, reboot device, swipe-away from recents.
4. Document expected behavior for each scenario as a checklist.

**Deliverable:** Behavioral test checklist.
**Risk:** None — documentation only.

---

#### Phase 1 — Fix Critical Receiver Flag (P-02)

**Goal:** Fix `LocationComplianceUnlockReceiver` to use `RECEIVER_EXPORTED` for `ACTION_USER_PRESENT`.

**Steps:**
1. In `LocationComplianceUnlockReceiver.kt`, change `RECEIVER_NOT_EXPORTED` to `RECEIVER_EXPORTED` in the `register()` method.
2. Verify `LockoutManager` already uses `RECEIVER_EXPORTED` (it does — no change needed).
3. Test on Android 13+ device: turn off location, wait for compliance countdown, lock device, unlock device — verify `onUserUnlocked()` fires and coordinator re-evaluates.
4. Test on Android 12 and below to verify no regression.

**Deliverable:** Single-line flag change + test verification.
**Risk:** Low — changing from `NOT_EXPORTED` to `EXPORTED` for a system broadcast is the correct behavior. The only risk is if the receiver was intentionally `NOT_EXPORTED` for a security reason, but since it listens for a system broadcast, `EXPORTED` is required.
**Rollback:** Revert the single flag change.

---

#### Phase 2 — Fix EventChannel Thread Safety (P-13)

**Goal:** Ensure all `LockEventChannel.emitX()` calls execute on the main thread.

**Steps:**
1. Add a `private val mainHandler = Handler(Looper.getMainLooper())` to `LockEventChannel`.
2. Wrap each `events?.success(...)` call in `mainHandler.post { ... }`.
3. Test: trigger permission changed event (from binder thread via `DeviceAdminReceiver`) — verify no crash.
4. Test: trigger lock result event (from main thread) — verify event still arrives in Flutter.

**Deliverable:** Thread-safe event emission.
**Risk:** Low — posting to main thread is standard practice for EventChannel. Events may be slightly delayed (posted to main thread queue) but ordering is preserved.
**Rollback:** Remove the `mainHandler.post` wrapper.

---

#### Phase 3 — Fix `LockCountdownTimer.cancel()` (P-03)

**Goal:** Make `cancel()` only remove the countdown timer's own callback, not all callbacks on the handler.

**Steps:**
1. Store the `Runnable` reference as a field (`private var countdownRunnable: Runnable? = null`).
2. In `start()`, assign the runnable to the field before `postDelayed`.
3. In `cancel()`, use `handler.removeCallbacks(countdownRunnable)` instead of `handler.removeCallbacksAndMessages(null)`.
4. Test: start countdown, cancel it, verify other handler callbacks (e.g., `scheduleSelfStop`) still fire.

**Deliverable:** Surgical cancel.
**Risk:** Low — only affects the timer's own callback.
**Rollback:** Revert to `removeCallbacksAndMessages(null)`.

---

#### Phase 4 — Fix Foreground Service Null-Intent Crash (P-09)

**Goal:** Ensure both foreground services call `startForeground()` even when restarted with null intent.

**Steps:**
1. In `AppLockForegroundService.onStartCommand`, add a null-intent path: if `intent == null` or `intent.action == null`, call `startForegroundService()` immediately (to satisfy the 5-second requirement), then read persisted state to determine if a countdown or timed lock was in progress.
2. In `LocationComplianceForegroundService.onStartCommand`, the `startForegroundService()` call is already unconditional — verify it runs before any other logic.
3. Test: start service, kill process via adb, verify service restarts and calls `startForeground()` without crashing.

**Deliverable:** Crash-safe service restart.
**Risk:** Medium — requires reading persisted state to determine what action to resume. If state is not persisted (e.g., countdown progress), the service may resume incorrectly.
**Rollback:** Revert to original `onStartCommand` logic.

---

#### Phase 5 — Fix Compliance Service Receiver Leak (P-17)

**Goal:** Ensure `LocationComplianceForegroundService` doesn't leak receivers on restart.

**Steps:**
1. Store the `LocationEnforcementController` instance as a service field (`private var enforcementController: LocationEnforcementController? = null`).
2. In `onStartCommand`, if `enforcementController` is not null, call `enforcementController?.stopRealtimeWatch()` before creating a new instance.
3. In `onDestroy`, call `enforcementController?.stopRealtimeWatch()`.
4. Test: start service, trigger restart (kill process), verify only one receiver is registered (check via `adb dumpsys activity broadcasts`).

**Deliverable:** No receiver leak.
**Risk:** Low — straightforward lifecycle management.
**Rollback:** Revert to fresh construction per `onStartCommand`.

---

#### Phase 6 — Introduce Shared `LocationEnforcementController` Instance (P-01)

**Goal:** Eliminate 8+ fresh constructions of `LocationEnforcementController`.

**Steps:**
1. Create an `EnforcementScope` (or use `Application` subclass) that owns a single `LocationEnforcementController` instance.
2. Provide access via a property or getter: `EnforcementScope.enforcementController`.
3. Replace all `LocationEnforcementController(context)` constructions with `EnforcementScope.enforcementController`.
4. Ensure `startRealtimeWatch()` / `stopRealtimeWatch()` are called once at scope creation/destruction, not per-method-call.
5. Update `LocationComplianceCoordinator`, `AppLockDeviceAdminReceiver`, `LocationComplianceForegroundService`, `LocationComplianceActivity`, `LocationEnforcementWorker`, `UninstallBlockController` to use the shared instance.
6. Test all compliance flows end-to-end.

**Deliverable:** Single shared controller instance.
**Risk:** High — touches 8+ files. Must verify that no code path assumes a fresh instance (e.g., if any code relies on the controller's internal state being reset per-construction).
**Rollback:** Revert to per-site construction.
**Dependency:** Should be done after Phase 5 (receiver leak fix) to avoid compounding the leak.

---

#### Phase 7 — Decouple `UninstallBlockController` from `LocationEnforcementController` (P-04)

**Goal:** Remove the `LocationEnforcementController` dependency from `UninstallBlockController`.

**Steps:**
1. Remove the `LocationEnforcementController` parameter from `UninstallBlockController`'s constructor.
2. Remove the `controller.apply()` call from `setBlocked()`.
3. If location enforcement needs to be triggered when uninstall blocking is toggled, add a callback/listener interface that `LocationComplianceCoordinator` (or `MainActivity`) registers.
4. Update `MainActivity.configureFlutterEngine` to construct `UninstallBlockController` without the enforcement controller.
5. Test: toggle uninstall blocking, verify location enforcement still triggers (via the callback).

**Deliverable:** Decoupled uninstall controller.
**Risk:** Medium — must ensure the location enforcement side effect is preserved via the new callback mechanism.
**Rollback:** Re-add the constructor parameter and direct call.
**Dependency:** Should be done after Phase 6 (shared controller) to simplify the callback wiring.

---

#### Phase 8 — Split `LocationComplianceCoordinator` (P-05)

**Goal:** Break the 635-line God Object into focused components.

**Steps:**
1. Extract `ComplianceStateMachine` — pure state transition logic, no Android dependencies. Takes current state + event, returns new state. Unit-testable.
2. Extract `ComplianceTimerService` — manages grace period and lock countdown via `Handler`. Takes callbacks for timer expiry.
3. Extract `ComplianceScreenManager` — starts/stops `LocationComplianceActivity`.
4. Extract `ComplianceServiceManager` — starts/stops `LocationComplianceForegroundService`.
5. Extract `ComplianceReceiverManager` — registers/unregisters `LocationComplianceUnlockReceiver`.
6. `LocationComplianceCoordinator` becomes a thin orchestrator (~100 lines) that delegates to the above components.
7. Test all compliance flows end-to-end.

**Deliverable:** Decomposed coordinator.
**Risk:** High — large refactor touching the core compliance logic. Must preserve exact state transition behavior.
**Rollback:** Revert to single coordinator.
**Dependency:** Should be done after Phase 6 (shared controller) and Phase 7 (decoupled uninstall).

---

#### Phase 9 — Replace `geolocator` with Native Channel (P-14)

**Goal:** Remove `geolocator` dependency; use native channel for location status.

**Steps:**
1. Add `isLocationEnabled` method to `LockMethodChannel` dispatch (routes to `LocationEnforcementController.isLocationEnabled()`).
2. Add `openLocationSettings` method to `LockMethodChannel` dispatch (routes to `Settings.ACTION_LOCATION_SOURCE_SETTINGS` intent).
3. Update `NativeDataSource` to expose these methods.
4. Update `LocationRepositoryImpl` to call `NativeDataSource` instead of `geolocator`.
5. Remove `geolocator` from `pubspec.yaml`.
6. Test: verify location status displays correctly, verify "open settings" button works.

**Deliverable:** No geolocator dependency.
**Risk:** Medium — must ensure the native `isLocationEnabled()` returns the same value as `geolocator.isLocationServiceEnabled()`. The native method uses `LocationManager.isProviderEnabled` for GPS and NETWORK providers; `geolocator` may use a different API.
**Rollback:** Re-add `geolocator` and revert `LocationRepositoryImpl`.
**Dependency:** Should be done after Phase 6 (shared controller) so the native method is accessible.

---

#### Phase 10 — Consolidate Foreground Services (P-08)

**Goal:** Merge `AppLockForegroundService` and `LocationComplianceForegroundService` into a single service.

**Steps:**
1. Create `EnforcementForegroundService` that handles both lock and compliance actions.
2. Use distinct notification IDs (1001 for lock, 1002 for compliance) within the single service.
3. Move all action handling from both services into the consolidated service.
4. Update `AppLockHandler` and `LocationComplianceCoordinator` to start the consolidated service with the appropriate action.
5. Update `AndroidManifest.xml` to declare only the consolidated service.
6. Test all service flows: lock now, lock for duration, start/stop service, compliance enforcement.

**Deliverable:** Single foreground service.
**Risk:** High — merges two independent service lifecycles. Must ensure notification updates correctly when switching between lock and compliance modes.
**Rollback:** Revert to two services.
**Dependency:** Should be done after Phase 8 (coordinator split) to simplify the service manager.

---

#### Phase 11 — Introduce Interface Abstractions (P-15, P-19)

**Goal:** Define interfaces for all controllers; handlers reference interfaces.

**Steps:**
1. Define `interface DeviceAdminOperations` with `lockNow()`, `lockForDuration()`, etc.
2. Define `interface LocationEnforcementOperations` with `isLocationEnabled()`, `apply()`, `startRealtimeWatch()`, etc.
3. Define `interface UninstallBlockOperations` with `setBlocked()`, `isBlocked()`, `isSupported()`.
4. Make `DeviceAdminController`, `LocationEnforcementController`, `UninstallBlockController` implement their respective interfaces.
5. Update handlers to reference interfaces, not concrete classes.
6. Update `MainActivity` to provide concrete implementations.
7. Replace `object` singletons with injected instances where feasible (keep `Constants` as `object` — it's stateless).

**Deliverable:** Interface-based architecture.
**Risk:** Medium — touches many files but changes are mechanical (interface extraction).
**Rollback:** Revert to concrete class references.
**Dependency:** Should be done after Phase 8 (coordinator split) to avoid rework.

---

### 5.3 Phase Dependency Graph

```
Phase 0 (Baseline)
  │
  ├──► Phase 1 (Receiver Flag)          ── standalone
  ├──► Phase 2 (EventChannel Threads)    ── standalone
  ├──► Phase 3 (Timer Cancel)            ── standalone
  ├──► Phase 4 (Null-Intent Crash)       ── standalone
  ├──► Phase 5 (Receiver Leak)           ── standalone
  │
  └──► Phase 6 (Shared Controller)
        │
        ├──► Phase 7 (Decouple Uninstall)
        ├──► Phase 9 (Remove geolocator)
        │
        └──► Phase 8 (Split Coordinator)
              │
              └──► Phase 10 (Consolidate Services)
                    │
                    └──► Phase 11 (Interfaces)
```

### 5.4 Risk Mitigation

| Risk | Mitigation |
|------|------------|
| Refactor breaks compliance enforcement | Test compliance flow after each phase; keep rollback path |
| Shared controller instance has stale state | Add state-reset method; call it on scope destruction |
| Coordinator split introduces state transition bugs | Extract state machine as pure function first; unit test all transitions before integrating |
| Service consolidation breaks notification behavior | Test notification visibility for each mode (lock vs compliance) |
| Interface extraction causes DI wiring errors | Use compile-time checks; verify all handler constructions in `MainActivity` |
| geolocator removal changes location detection behavior | Compare native `isLocationEnabled()` vs `geolocator` output on multiple devices before removing |

### 5.5 Success Criteria

1. **No behavior change** — all user flows work identically before and after refactoring.
2. **No receiver leaks** — `adb dumpsys activity broadcasts` shows exactly one `LocationProviderChangeReceiver` registered at any time.
3. **No thread-safety crashes** — `emitPermissionChanged` from binder thread does not crash.
4. **No null-intent crashes** — service restarts after process death do not crash.
5. **Single source of truth for location** — `geolocator` removed; native channel is the only path.
6. **Testable components** — `ComplianceStateMachine` can be unit tested without Android dependencies.
7. **No God Objects** — no file exceeds ~300 lines; each class has a single responsibility.
8. **No static mutable singletons** (except `Constants`) — all state is injected or lifecycle-scoped.

---

## Appendix A — File Inventory

### Flutter (Dart)

| File | Lines | Responsibility |
|------|-------|----------------|
| [`main.dart`](lib/main.dart:1) | ~15 | Entry point, GetStorage init |
| [`app.dart`](lib/app.dart:1) | ~30 | GetMaterialApp, routing |
| [`dependency_injection.dart`](lib/config/dependency_injection.dart:1) | ~60 | GetX bindings |
| [`app_method_channel.dart`](lib/core/platform/app_method_channel.dart:1) | ~80 | MethodChannel wrapper |
| [`app_event_channel.dart`](lib/core/platform/app_event_channel.dart:1) | ~70 | EventChannel wrapper |
| [`native_data_source.dart`](lib/data/datasources/native_data_source.dart:1) | ~120 | Platform channel calls |
| [`local_data_source.dart`](lib/data/datasources/local_data_source.dart:1) | ~40 | GetStorage wrapper |
| 5× repository impl | ~50 each | Repository implementations |
| 5× repository interface | ~15 each | Repository contracts |
| 18× usecase | ~15 each | Single-purpose use cases |
| 4× entity | ~20 each | Domain models |
| [`status_controller.dart`](lib/presentation/status/controllers/status_controller.dart:1) | ~150 | Status screen logic |
| [`welcome_controller.dart`](lib/presentation/welcome/controllers/welcome_controller.dart:1) | ~60 | Welcome screen logic |

### Native (Kotlin)

| File | Lines | Responsibility |
|------|-------|----------------|
| [`MainActivity.kt`](android/app/src/main/kotlin/com/example/app_lock/MainActivity.kt:35) | ~119 | Channel wiring, handler construction |
| [`LockMethodChannel.kt`](android/app/src/main/kotlin/com/example/app_lock/channels/LockMethodChannel.kt:26) | ~73 | Method dispatch |
| [`LockEventChannel.kt`](android/app/src/main/kotlin/com/example/app_lock/channels/LockEventChannel.kt:24) | ~181 | Event stream |
| [`AppLockHandler.kt`](android/app/src/main/kotlin/com/example/app_lock/channels/handlers/AppLockHandler.kt:22) | ~125 | Lock/service methods |
| [`PermissionHandler.kt`](android/app/src/main/kotlin/com/example/app_lock/channels/handlers/PermissionHandler.kt:52) | ~162 | Permission methods |
| [`UninstallHandler.kt`](android/app/src/main/kotlin/com/example/app_lock/channels/handlers/UninstallHandler.kt:16) | ~51 | Uninstall methods |
| [`DeviceAdminController.kt`](android/app/src/main/kotlin/com/example/app_lock/admin/DeviceAdminController.kt:33) | ~150 | DPM operations |
| [`LocationEnforcementController.kt`](android/app/src/main/kotlin/com/example/app_lock/admin/LocationEnforcementController.kt:63) | ~368 | Location enforcement |
| [`LocationEnforcementWorker.kt`](android/app/src/main/kotlin/com/example/app_lock/admin/LocationEnforcementWorker.kt:61) | ~98 | WorkManager worker |
| [`LocationProviderChangeReceiver.kt`](android/app/src/main/kotlin/com/example/app_lock/admin/LocationProviderChangeReceiver.kt:70) | ~190 | PROVIDERS_CHANGED receiver |
| [`UninstallBlockController.kt`](android/app/src/main/kotlin/com/example/app_lock/admin/UninstallBlockController.kt:36) | ~284 | Uninstall blocking |
| [`AppLockDeviceAdminReceiver.kt`](android/app/src/main/kotlin/com/example/app_lock/admin/AppLockDeviceAdminReceiver.kt:27) | ~141 | DeviceAdminReceiver |
| [`LocationComplianceCoordinator.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceCoordinator.kt:76) | ~635 | State machine orchestrator |
| [`LocationComplianceState.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceState.kt:81) | ~124 | State enum |
| [`LocationComplianceStateStore.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceStateStore.kt:68) | ~228 | SharedPreferences persistence |
| [`LocationComplianceActivity.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceActivity.kt:74) | ~337 | Compliance screen |
| [`LocationComplianceForegroundService.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceForegroundService.kt:64) | ~166 | Compliance FG service |
| [`LocationComplianceBootReceiver.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceBootReceiver.kt:45) | ~69 | BOOT_COMPLETED receiver |
| [`LocationComplianceUnlockReceiver.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceUnlockReceiver.kt:57) | ~130 | USER_PRESENT receiver |
| [`LockTaskController.kt`](android/app/src/main/kotlin/com/example/app_lock/compliance/LockTaskController.kt:60) | ~194 | Lock Task Mode |
| [`AppLockForegroundService.kt`](android/app/src/main/kotlin/com/example/app_lock/service/AppLockForegroundService.kt:43) | ~312 | Lock FG service |
| [`LockCountdownTimer.kt`](android/app/src/main/kotlin/com/example/app_lock/service/LockCountdownTimer.kt:24) | ~74 | Countdown timer |
| [`LockoutManager.kt`](android/app/src/main/kotlin/com/example/app_lock/service/LockoutManager.kt:49) | ~244 | Lockout state manager |
| [`PermissionEventBus.kt`](android/app/src/main/kotlin/com/example/app_lock/permissions/PermissionEventBus.kt:20) | ~64 | Event bus |
| [`ActivityLauncher.kt`](android/app/src/main/kotlin/com/example/app_lock/permissions/ActivityLauncher.kt:1) | ~15 | Activity launch abstraction |
| [`Constants.kt`](android/app/src/main/kotlin/com/example/app_lock/util/Constants.kt:13) | ~158 | Constants |

---

## Appendix B — Problem-to-Phase Cross-Reference

| Problem ID | Phase | Status |
|------------|-------|--------|
| P-01 | Phase 6 | Planned |
| P-02 | Phase 1 | Planned |
| P-03 | Phase 3 | Planned |
| P-04 | Phase 7 | Planned |
| P-05 | Phase 8 | Planned |
| P-06 | Phase 11 | Planned |
| P-07 | Phase 11 | Planned |
| P-08 | Phase 10 | Planned |
| P-09 | Phase 4 | Planned |
| P-10 | Phase 6 (uses UPDATE policy) | Planned |
| P-11 | Phase 6 (worker calls coordinator) | Planned |
| P-12 | Backlog (document return value semantics) | Planned |
| P-13 | Phase 2 | Planned |
| P-14 | Phase 9 | Planned |
| P-15 | Phase 11 | Planned |
| P-16 | Phase 8 (activity receives injected controllers) | Planned |
| P-17 | Phase 5 | Planned |
| P-18 | Backlog (query ActivityManager instead) | Planned |
| P-19 | Phase 11 (interface-based dispatch) | Planned |
| P-20 | Backlog (document pull-before-push ordering) | Planned |
| P-21 | Backlog (document wall-clock tradeoff) | Planned |
| P-22 | Backlog (use receiver instead of polling) | Planned |

---

*End of Architecture Analysis Document*
