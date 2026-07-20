# 📁 App Lock — Project Structure & File Map

> **Demo Installment Locker Application** — a Flutter + Android (Kotlin) app that demonstrates a production-grade Android app-locking workflow using Device Owner / Device Policy Manager APIs, a foreground service, real-time location-compliance enforcement, and a Clean Architecture codebase.

---

## 1. Technology Stack

| Layer | Technology |
|-------|-----------|
| **Framework** | Flutter (Dart `^3.11.4`) |
| **State / DI / Routing** | GetX `^4.6.6` |
| **Local persistence** | get_storage `^2.1.1` |
| **Logging** | logger `^2.4.0` |
| **Responsive UI** | flutter_screenutil `^5.9.3` |
| **Vector assets** | flutter_svg `^2.0.10+1` |
| **Device / package info** | device_info_plus `^10.1.2`, package_info_plus `^8.0.0` |
| **Location** | geolocator `^14.0.2` |
| **Native (Android)** | Kotlin, AndroidX, Material Components `1.12.0`, WorkManager, DevicePolicyManager |
| **Architecture** | Clean Architecture (Presentation → Domain → Data) + MVVM |

---

## 2. Architecture Overview

The project follows **Clean Architecture** with a strict dependency rule:

```
Presentation  →  Domain  ←  Data
    │              ▲          │
    └── depends on ┘          └── implements domain abstractions
```

- **Domain** — pure Dart. Entities, repository abstractions, and use cases. No Flutter/GetX/third-party imports.
- **Data** — implements the repository abstractions. Talks to platform channels and the `geolocator` plugin.
- **Presentation** — GetX controllers (ViewModels), views, bindings, routes. The only layer that imports Flutter widgets.
- **Core** — cross-cutting infrastructure (constants, theme, logging, platform-channel wrappers, errors, utils).
- **Config** — the dependency-injection composition root.

### State synchronisation — dual-path pattern

Every status uses **pull + push**:
1. **Pull** — on `init` / resume, the current value is fetched via a `Check*UseCase`.
2. **Push** — a `Watch*UseCase` subscribes to a native `EventChannel` (or `geolocator` stream) so the UI updates the instant the state changes — even when the change happens in the background (e.g. a countdown-triggered lock).

---

## 3. Complete File Tree

### 3.1 Flutter (Dart) — `lib/`

```
lib/
├── main.dart                                  # Entry point: init storage → DI → runApp
├── app.dart                                   # Root GetMaterialApp widget (theme + routes)
│
├── config/
│   └── dependency_injection.dart              # Composition root: registers channels, sources, repos
│
├── core/
│   ├── constants/
│   │   ├── app_constants.dart                 # App-wide constants (name, storage container, SDK, app id)
│   │   ├── channel_constants.dart             # Platform-channel names + method/event keys (Flutter↔Native contract)
│   │   └── storage_keys.dart                  # GetStorage key names
│   │
│   ├── errors/
│   │   └── app_error.dart                     # Sealed error hierarchy (PlatformError, etc.)
│   │
│   ├── platform/
│   │   ├── app_method_channel.dart            # Typed wrapper over MethodChannel (bool/string calls)
│   │   └── app_event_channel.dart             # Typed broadcast Stream wrapper over EventChannel
│   │
│   ├── theme/
│   │   └── app_theme.dart                     # Material 3 light/dark themes
│   │
│   └── utils/
│       ├── app_logger.dart                    # Wrapper over `logger` package (leveled logging)
│       ├── responsive_helper.dart             # Screenutil sizing helpers
│       └── result.dart                        # Sealed Result<S,F> (Success / Failure) union
│
├── data/
│   ├── datasources/
│   │   ├── local_data_source.dart             # GetStorage-backed key/value reads & writes
│   │   └── native_data_source.dart            # Platform-channel calls → raw native payloads
│   │
│   └── repositories/
│       ├── background_service_repository_impl.dart   # Implements BackgroundServiceRepository
│       ├── location_repository_impl.dart            # Implements LocationRepository (geolocator)
│       ├── lock_repository_impl.dart                # Implements LockRepository
│       ├── permission_repository_impl.dart          # Implements PermissionRepository
│       └── uninstall_repository_impl.dart           # Implements UninstallRepository
│
├── domain/
│   ├── entities/
│   │   ├── app_state.dart                     # Aggregate state snapshot (exports the 3 enums below)
│   │   ├── lock_result.dart                   # LockResult enum (success / denied / unsupported / failed)
│   │   ├── permission_status.dart             # PermissionStatus enum (unknown / granted / denied)
│   │   └── service_status.dart                # ServiceStatus enum (stopped / running / error)
│   │
│   ├── repositories/
│   │   ├── background_service_repository.dart # Abstract port: start/stop/isRunning + state stream
│   │   ├── location_repository.dart           # Abstract port: isLocationEnabled + status stream + open settings
│   │   ├── lock_repository.dart               # Abstract port: lockNow / lockForDuration + result/lockout streams
│   │   ├── permission_repository.dart         # Abstract port: check/request/isProvisioningAllowed + stream
│   │   └── uninstall_repository.dart          # Abstract port: setBlocked/isBlocked/isSupported + stream
│   │
│   └── usecases/
│       ├── check_location_enabled_usecase.dart
│       ├── check_permission_usecase.dart
│       ├── check_provisioning_allowed_usecase.dart
│       ├── is_uninstall_block_supported_usecase.dart
│       ├── is_uninstall_blocked_usecase.dart
│       ├── lock_for_duration_usecase.dart
│       ├── lock_now_usecase.dart
│       ├── open_location_settings_usecase.dart
│       ├── request_permission_usecase.dart
│       ├── set_uninstall_blocked_usecase.dart
│       ├── start_service_usecase.dart
│       ├── stop_service_usecase.dart
│       ├── watch_location_status_usecase.dart
│       ├── watch_lock_result_usecase.dart
│       ├── watch_lockout_state_usecase.dart
│       ├── watch_permission_usecase.dart
│       ├── watch_service_state_usecase.dart
│       └── watch_uninstall_block_usecase.dart
│
└── presentation/
    ├── routes/
    │   └── app_routes.dart                    # GetX route table (/welcome, /status) + bindings
    │
    ├── widgets/
    │   ├── section_header.dart                # Reusable section header widget
    │   └── status_badge.dart                  # Reusable status badge widget
    │
    ├── welcome/
    │   ├── bindings/
    │   │   └── welcome_binding.dart           # Lazy DI for WelcomeController + use cases
    │   ├── controllers/
    │   │   └── welcome_controller.dart        # Welcome ViewModel (permission + location dual-confirmation)
    │   └── views/
    │       └── welcome_view.dart              # Welcome screen UI
    │
    └── status/
        ├── bindings/
        │   └── status_binding.dart            # Lazy DI for StatusController + use cases
        ├── controllers/
        │   └── status_controller.dart         # Status ViewModel (demo run, lock, uninstall block)
        └── views/
            └── status_view.dart               # Status screen UI
```

### 3.2 Android (Kotlin) — `android/app/src/main/`

```
android/app/src/main/
├── AndroidManifest.xml                        # App + compliance components registration
│
├── kotlin/com/example/app_lock/
│   │
│   ├── MainActivity.kt                        # FlutterActivity; configures Method/Event channels
│   │
│   ├── admin/                                 # ── Device Admin / Policy layer ──
│   │   ├── AppLockDeviceAdminReceiver.kt      # DeviceAdminReceiver; enforces location on enable
│   │   ├── DeviceAdminController.kt           # lockNow / lockForDuration via DevicePolicyManager
│   │   ├── LocationEnforcementController.kt   # Force location-on + realtime watch + WorkManager
│   │   ├── LocationEnforcementWorker.kt       # CoroutineWorker: periodic re-apply + state reconcile
│   │   ├── LocationProviderChangeReceiver.kt  # PROVIDERS_CHANGED → compliance coordinator
│   │   └── UninstallBlockController.kt        # DISALLOW_UNINSTALL_APPS user restriction
│   │
│   ├── channels/                              # ── Platform channel handlers ──
│   │   ├── LockMethodChannel.kt               # MethodChannel.MethodCallHandler dispatch
│   │   ├── LockEventChannel.kt                # EventChannel.StreamHandler + emit helpers
│   │   └── handlers/
│   │       ├── AppLockHandler.kt              # lockNow / lockForDuration / start/stop service
│   │       ├── PermissionHandler.kt           # checkPermission / requestPermission / provisioning
│   │       └── UninstallHandler.kt            # setUninstallBlocked / isBlocked / isSupported
│   │
│   ├── compliance/                            # ── Location Compliance Enforcement System ──
│   │   ├── LocationComplianceCoordinator.kt   # State machine brain (6 states, lockNow escalation)
│   │   ├── LocationComplianceState.kt         # Enum: NORMAL→LOCATION_OFF→COUNTDOWN→COMPLIANCE→LOCKED→RESTORED
│   │   ├── LocationComplianceStateStore.kt    # SharedPreferences persistence (survives reboot/death)
│   │   ├── LocationComplianceActivity.kt      # Full-screen non-dismissible Material compliance UI
│   │   ├── LocationComplianceForegroundService.kt  # FGS (special-use) keeps process alive
│   │   ├── LocationComplianceBootReceiver.kt  # BOOT_COMPLETED → restore state
│   │   ├── LocationComplianceUnlockReceiver.kt# ACTION_USER_PRESENT → re-verify loop
│   │   └── LockTaskController.kt              # Lock Task Mode allowlist + feature flags
│   │
│   ├── permissions/
│   │   ├── ActivityLauncher.kt                # startActivityForResult helper
│   │   └── PermissionEventBus.kt             # In-process permission result bus
│   │
│   ├── service/                               # ── Foreground service + lockout engine ──
│   │   ├── AppLockForegroundService.kt        # Countdown + timed-lock foreground service
│   │   ├── LockCountdownTimer.kt              # Countdown timer abstraction
│   │   └── LockoutManager.kt                  # Post-lock re-lock window (ACTION_USER_PRESENT)
│   │
│   └── util/
│       └── Constants.kt                       # Native-side constants (channels, durations, IDs)
│
└── res/
    ├── drawable/
    │   ├── ic_lock.xml                        # Lock vector icon
    │   └── launch_background.xml              # Launch screen background
    ├── drawable-v21/
    │   └── launch_background.xml              # API 21+ launch background
    ├── layout/
    │   └── activity_location_compliance.xml   # Material 3 compliance screen layout
    ├── mipmap-*/                              # Launcher icons (hdpi → xxxhdpi)
    ├── values/
    │   ├── strings.xml                        # App + compliance strings
    │   └── styles.xml                         # NormalTheme + ComplianceTheme (Material3.DayNight)
    ├── values-night/
    │   └── styles.xml                         # Night theme variants
    └── xml/
        └── device_admin.xml                   # DeviceAdminReceiver policy metadata
```

### 3.3 Platform-specific & config

```
android/app/
├── build.gradle.kts                           # App module: deps, SDK, Material 1.12.0, Activity-KTX
└── src/
    ├── debug/AndroidManifest.xml              # Debug manifest overlay
    └── profile/AndroidManifest.xml            # Profile manifest overlay

android/
├── build.gradle.kts                           # Root Gradle config
├── settings.gradle.kts                        # Plugin + repo settings
└── gradle.properties                          # Gradle JVM/AndroidX flags

ios/                                           # iOS runner (AppDelegate, Info.plist, assets)
macos/                                         # macOS runner
linux/  windows/  web/                         # Desktop + web runners (generated)

test/
└── widget_test.dart                           # Widget test scaffold

plans/
└── complete-architecture-review.md            # Detailed architecture audit

pubspec.yaml                                   # Flutter dependencies + assets
analysis_options.yaml                          # Dart linter rules
README.md                                      # Project readme
PROJECT_STRUCTURE.md                           # ← This file
```

---

## 4. Layer Responsibilities

### 4.1 Core (`lib/core/`)

Cross-cutting infrastructure shared by all layers. Never depends on domain/data/presentation.

| File | Responsibility |
|------|---------------|
| [`app_constants.dart`](lib/core/constants/app_constants.dart) | App name, storage container name, min SDK, application id, DeviceAdmin component |
| [`channel_constants.dart`](lib/core/constants/channel_constants.dart) | The Flutter↔Native contract: channel names, method names, event types, payload keys |
| [`app_method_channel.dart`](lib/core/platform/app_method_channel.dart) | Typed wrapper — `invokeBoolMethod` / `invokeStringMethod` with `PlatformError` conversion |
| [`app_event_channel.dart`](lib/core/platform/app_event_channel.dart) | Lazily-created shared broadcast `Stream<Map<String,dynamic>>` |
| [`result.dart`](lib/core/utils/result.dart) | Sealed `Result<S,F>` — `Success` / `Failure` for explicit error handling |
| [`app_logger.dart`](lib/core/utils/app_logger.dart) | Leveled logging wrapper (rest of app never imports `logger` directly) |
| [`app_theme.dart`](lib/core/theme/app_theme.dart) | Material 3 light/dark `ThemeData` |

### 4.2 Domain (`lib/domain/`)

Pure Dart business logic. No Flutter, no GetX, no third-party packages.

- **Entities** — immutable value objects: [`AppState`](lib/domain/entities/app_state.dart), [`LockResult`](lib/domain/entities/lock_result.dart), [`PermissionStatus`](lib/domain/entities/permission_status.dart), [`ServiceStatus`](lib/domain/entities/service_status.dart).
- **Repositories** — abstract ports (interfaces) declaring what the data layer must provide.
- **Use cases** — single-responsibility interactors (one class per action). Controllers depend on these, never on repositories directly.

### 4.3 Data (`lib/data/`)

Implements the domain repository abstractions.

- [`NativeDataSource`](lib/data/datasources/native_data_source.dart) — the **only** class that touches `AppMethodChannel` / `AppEventChannel`. Translates raw native payloads into domain entities.
- [`LocalDataSource`](lib/data/datasources/local_data_source.dart) — GetStorage key/value store for demo flags.
- `*_repository_impl.dart` — bind the abstractions to the data sources.

### 4.4 Presentation (`lib/presentation/`)

GetX MVVM. Controllers hold `Rx` state; views observe via `Obx`.

- **Welcome screen** — Device Owner permission + location dual-confirmation gate. Shows the `adb dpm set-device-owner` command when in-app provisioning isn't available.
- **Status screen** — demo control panel: start/stop service, lock now, lock for duration, uninstall block toggle. Aggregates all status into one [`AppState`](lib/domain/entities/app_state.dart) snapshot.

### 4.5 Config (`lib/config/`)

[`DependencyInjection.init()`](lib/config/dependency_injection.dart) — the single composition root. Registers platform channels, storage, data sources, and repositories as permanent singletons. Use cases and controllers are registered lazily by their bindings.

---

## 5. Platform Channel Contract

The Flutter↔Native boundary is defined in [`channel_constants.dart`](lib/core/constants/channel_constants.dart) and mirrored in [`Constants.kt`](android/app/src/main/kotlin/com/example/app_lock/util/Constants.kt).

### 5.1 MethodChannel — `app_lock/methods`

| Method | Args | Returns | Description |
|--------|------|---------|-------------|
| `checkPermission` | — | `bool` | Device Owner / admin active? |
| `requestPermission` | — | `bool` | Launch managed-provisioning flow |
| `isProvisioningAllowed` | — | `bool` | In-app DO provisioning possible? |
| `lockNow` | — | `bool` | Immediate `DevicePolicyManager.lockNow()` |
| `lockForDuration` | `durationMs: int` | `bool` | Lock + re-lock window |
| `startService` | — | `bool` | Start foreground service |
| `stopService` | — | `bool` | Stop foreground service |
| `isServiceRunning` | — | `bool` | Service running? |
| `setUninstallBlocked` | `blocked: bool` | `bool` | Toggle `DISALLOW_UNINSTALL_APPS` |
| `isUninstallBlocked` | — | `bool` | Restriction active? |
| `isUninstallBlockSupported` | — | `bool` | DO/PO supported? |

### 5.2 EventChannel — `app_lock/events`

| Event type | Payload key | Value | Description |
|-----------|-------------|-------|-------------|
| `permissionChanged` | `granted` | `bool` | Admin enabled/disabled |
| `lockResult` | `result` | `String` | Lock command outcome |
| `serviceStateChanged` | `status` | `String` | Service started/stopped |
| `lockoutStateChanged` | `active` | `bool` | Re-lock window open/close |
| `uninstallBlockChanged` | `blocked` | `bool` | Restriction applied/removed |

---

## 6. Native (Kotlin) Package Map

| Package | Responsibility |
|---------|---------------|
| `admin` | Device Policy Manager operations: device-admin receiver, lock controller, location enforcement, uninstall block |
| `channels` | Platform-channel dispatch (`LockMethodChannel`) + per-feature handlers (`AppLockHandler`, `PermissionHandler`, `UninstallHandler`) + event sink (`LockEventChannel`) |
| `compliance` | **Location Compliance Enforcement System** — state machine, persistence, foreground service, boot/unlock receivers, Lock Task controller, compliance Activity |
| `permissions` | `ActivityLauncher` (startActivityForResult) + `PermissionEventBus` (in-process result bus) |
| `service` | `AppLockForegroundService` (countdown + timed lock), `LockCountdownTimer`, `LockoutManager` (post-lock re-lock) |
| `util` | `Constants` — channel names, durations, notification IDs |

---

## 7. Location Compliance Enforcement System

A self-healing state machine that forces Device Location to stay enabled. Survives app close, screen off, process death, and reboot.

### 7.1 State machine

```
NORMAL → LOCATION_OFF → COUNTDOWN_RUNNING → COMPLIANCE_SCREEN → LOCKED
   ▲                                                        │
   └──────────── LOCATION_RESTORED ←─────────────────────────┘
```

- **2-minute grace countdown** before escalation.
- On expiry → `DevicePolicyManager.lockNow()` → device locks.
- On unlock (`ACTION_USER_PRESENT`) → re-verify; if still off, loop again.

### 7.2 Survival layers

| Component | Trigger | Role |
|-----------|---------|------|
| [`LocationComplianceForegroundService`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceForegroundService.kt) | `START_STICKY` | Keeps process alive; restores state on restart |
| [`LocationComplianceBootReceiver`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceBootReceiver.kt) | `BOOT_COMPLETED` | Restore after reboot |
| [`LocationComplianceUnlockReceiver`](android/app/src/main/kotlin/com/example/app_lock/compliance/LocationComplianceUnlockReceiver.kt) | `ACTION_USER_PRESENT` | Re-verify loop after unlock |
| [`LocationEnforcementWorker`](android/app/src/main/kotlin/com/example/app_lock/admin/LocationEnforcementWorker.kt) | WorkManager 15-min | Periodic re-apply + state reconcile |
| [`LocationProviderChangeReceiver`](android/app/src/main/kotlin/com/example/app_lock/admin/LocationProviderChangeReceiver.kt) | `PROVIDERS_CHANGED` | Real-time detection → coordinator |

### 7.3 Lock Task Mode

[`LockTaskController`](android/app/src/main/kotlin/com/example/app_lock/compliance/LockTaskController.kt) pins the compliance Activity via `setLockTaskPackages` + `setLockTaskFeatures` (`SYSTEM_INFO | NOTIFICATIONS`) so the user can reach Location Settings but cannot escape to arbitrary apps.

---

## 8. Data Flow Example — "Lock Now" button tap

```
StatusView (Obx)
   │ onTap → controller.lockNow()
   ▼
StatusController.lockNow()
   │ calls use case
   ▼
LockNowUseCase.call()
   │ delegates to repository
   ▼
LockRepositoryImpl.lockNow()
   │ calls data source
   ▼
NativeDataSource.lockNow()
   │ invokes method channel
   ▼
AppMethodChannel.invokeBoolMethod('lockNow')
   │ platform call
   ▼
LockMethodChannel.onMethodCall  (Kotlin)
   │ dispatches to handler
   ▼
AppLockHandler.lockNow()
   │ uses DevicePolicyManager
   ▼
DeviceAdminController.performLock()
   │ lockNow() + emit result
   ▼
LockEventChannel.emitLockResult()  ──►  EventChannel
   │                                          │
   ▲                                          ▼
NativeDataSource.lockResults stream  ◄── broadcast
   │
LockRepositoryImpl.lockResults
   │
WatchLockResultUseCase  ──►  StatusController subscription  ──►  Rx<AppState>  ──►  UI updates
```

---

## 9. Key Design Decisions

1. **Single framework (GetX)** — state, DI, and routing all in one package to avoid overlapping responsibilities.
2. **Sealed `Result<S,F>`** — repositories return explicit failures instead of throwing.
3. **Dual-path state sync** — pull on init/resume + push via EventChannel streams.
4. **Typed channel wrappers** — `AppMethodChannel` / `AppEventChannel` convert `PlatformException` into `PlatformError`; the rest of the app never sees raw channel APIs.
5. **Lazy controller DI** — bindings create controllers/use cases on route entry and dispose on exit; only channels/repos are permanent singletons.
6. **Compliance system is fully native** — the state machine, persistence, and enforcement run in Kotlin so they survive Flutter process death. Flutter only observes status via the existing EventChannel.
7. **Official Android Enterprise APIs only** — `DevicePolicyManager`, Lock Task Mode, `FOREGROUND_SERVICE_SPECIAL_USE`. No unsupported workarounds.

---

*Generated for `app_lock` v1.0.0+1 — Flutter `^3.11.4`, Android Kotlin, Material 3.*
