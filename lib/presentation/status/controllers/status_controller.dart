import 'dart:async';

import 'package:get/get.dart';

import '../../../core/constants/app_constants.dart';
import '../../../core/utils/app_logger.dart';
import '../../../domain/entities/app_state.dart';
import '../../../domain/usecases/check_permission_usecase.dart';
import '../../../domain/usecases/disable_kiosk_mode_usecase.dart';
import '../../../domain/usecases/enable_kiosk_mode_usecase.dart';
import '../../../domain/usecases/is_uninstall_block_supported_usecase.dart';
import '../../../domain/usecases/is_uninstall_blocked_usecase.dart';
import '../../../domain/usecases/lock_for_duration_usecase.dart';
import '../../../domain/usecases/lock_now_usecase.dart';
import '../../../domain/usecases/set_uninstall_blocked_usecase.dart';
import '../../../domain/usecases/start_service_usecase.dart';
import '../../../domain/usecases/stop_service_usecase.dart';
import '../../../domain/usecases/watch_lock_result_usecase.dart';
import '../../../domain/usecases/watch_lockout_state_usecase.dart';
import '../../../domain/usecases/watch_service_state_usecase.dart';
import '../../../domain/usecases/watch_uninstall_block_usecase.dart';

/// ViewModel for the Status screen.
///
/// Aggregates the demo's runtime state into a single reactive [AppState]
/// snapshot and exposes the four Status-screen actions: start demo, stop
/// demo, lock now, and check permission. The static `OVERDUE` flag that
/// drives the lock decision lives here as a demo constant.
///
/// State synchronisation (architecture review §16.3 — dual-path):
/// - **Pull path**: [startDemo] / [stopDemo] / [lockNow] invoke use cases and
///   update state from the returned result.
/// - **Push path**: subscriptions to [WatchServiceStateUseCase] and
///   [WatchLockResultUseCase] update state the moment the native foreground
///   service emits an event. This is what lets the UI reflect a *background*
///   lock (triggered by the countdown timer) even though no Flutter method
///   call initiated it.
class StatusController extends GetxController {
  /// Constructs the controller with its use-case dependencies.
  StatusController({
    required CheckPermissionUseCase checkPermissionUseCase,
    required StartServiceUseCase startServiceUseCase,
    required StopServiceUseCase stopServiceUseCase,
    required LockNowUseCase lockNowUseCase,
    required LockForDurationUseCase lockForDurationUseCase,
    required WatchServiceStateUseCase watchServiceStateUseCase,
    required WatchLockResultUseCase watchLockResultUseCase,
    required WatchLockoutStateUseCase watchLockoutStateUseCase,
    required SetUninstallBlockedUseCase setUninstallBlockedUseCase,
    required IsUninstallBlockedUseCase isUninstallBlockedUseCase,
    required IsUninstallBlockSupportedUseCase isUninstallBlockSupportedUseCase,
    required WatchUninstallBlockUseCase watchUninstallBlockUseCase,
    required EnableKioskModeUseCase enableKioskModeUseCase,
    required DisableKioskModeUseCase disableKioskModeUseCase,
  }) : _checkPermissionUseCase = checkPermissionUseCase,
       _startServiceUseCase = startServiceUseCase,
       _stopServiceUseCase = stopServiceUseCase,
       _lockNowUseCase = lockNowUseCase,
       _lockForDurationUseCase = lockForDurationUseCase,
       _watchServiceStateUseCase = watchServiceStateUseCase,
       _watchLockResultUseCase = watchLockResultUseCase,
       _watchLockoutStateUseCase = watchLockoutStateUseCase,
       _setUninstallBlockedUseCase = setUninstallBlockedUseCase,
       _isUninstallBlockedUseCase = isUninstallBlockedUseCase,
       _isUninstallBlockSupportedUseCase = isUninstallBlockSupportedUseCase,
       _watchUninstallBlockUseCase = watchUninstallBlockUseCase,
       _enableKioskModeUseCase = enableKioskModeUseCase,
       _disableKioskModeUseCase = disableKioskModeUseCase;

  final CheckPermissionUseCase _checkPermissionUseCase;
  final StartServiceUseCase _startServiceUseCase;
  final StopServiceUseCase _stopServiceUseCase;
  final LockNowUseCase _lockNowUseCase;
  final LockForDurationUseCase _lockForDurationUseCase;
  final WatchServiceStateUseCase _watchServiceStateUseCase;
  final WatchLockResultUseCase _watchLockResultUseCase;
  final WatchLockoutStateUseCase _watchLockoutStateUseCase;
  final SetUninstallBlockedUseCase _setUninstallBlockedUseCase;
  final IsUninstallBlockedUseCase _isUninstallBlockedUseCase;
  final IsUninstallBlockSupportedUseCase _isUninstallBlockSupportedUseCase;
  final WatchUninstallBlockUseCase _watchUninstallBlockUseCase;
  final EnableKioskModeUseCase _enableKioskModeUseCase;
  final DisableKioskModeUseCase _disableKioskModeUseCase;

  /// Reactive aggregate state rendered by the Status screen.
  final Rx<AppState> state = const AppState(
    permissionStatus: PermissionStatus.unknown,
    serviceStatus: ServiceStatus.stopped,
    lockResult: LockResult.success,
    isDemoRunning: false,
  ).obs;

  /// Whether an action is currently in flight.
  final RxBool isBusy = false.obs;

  /// Whether Android Lock Task Mode (screen pinning) is currently active,
  /// disabling the Back / Home / Recents buttons while the Status screen is
  /// visible. Toggled on in [onInit] and off in [onClose] / [exitKiosk].
  final RxBool isKioskActive = false.obs;

  /// User-selected lock duration in minutes for the timed-lock feature.
  final RxInt selectedLockMinutes = 5.obs;

  /// Preset durations (in minutes) offered in the duration picker.
  List<int> get lockDurationPresets => const <int>[1, 2, 3, 4, 5, 10, 15, 30, 60];

  /// Static demo flag — in a real app this would come from a backend.
  /// For the demo it is always `true`, meaning the device is "overdue"
  /// and should be locked once the timer fires.
  static const bool isOverdue = true;

  StreamSubscription<ServiceStatus>? _serviceStateSub;
  StreamSubscription<LockResult>? _lockResultSub;
  StreamSubscription<bool>? _lockoutStateSub;
  StreamSubscription<bool>? _uninstallBlockSub;

  @override
  void onInit() {
    super.onInit();
    refreshPermission();
    refreshUninstallBlock();
    _subscribeServiceState();
    _subscribeLockResult();
    _subscribeLockoutState();
    _subscribeUninstallBlock();
    // Pin this Activity via Android Lock Task Mode (screen pinning) so the
    // Back / Home / Recents buttons are disabled while the Status screen is
    // visible. Requires the app to be Device Owner; on unsupported devices
    // the call is a no-op and [isKioskActive] stays `false`.
    enableKiosk();
  }

  /// Enables Android Lock Task Mode (screen pinning) for the host Activity.
  ///
  /// On success the Back / Home / Recents buttons are disabled and
  /// [isKioskActive] flips to `true`. On unsupported devices (not Device
  /// Owner) the call returns `false` and the UI remains dismissible.
  Future<void> enableKiosk() async {
    try {
      final bool ok = await _enableKioskModeUseCase.call();
      isKioskActive.value = ok;
      if (ok) {
        AppLogger.debug('Kiosk mode (Lock Task Mode) enabled');
      } else {
        AppLogger.warning('Kiosk mode not enabled — device not supported');
      }
    } catch (e) {
      AppLogger.error('Failed to enable kiosk mode', error: e);
    }
  }

  /// Releases the Lock Task Mode pin and (optionally) navigates away.
  ///
  /// Called by the Status screen's "Exit" affordance. After releasing, the
  /// system navigation buttons behave normally again.
  Future<void> exitKiosk() async {
    try {
      final bool ok = await _disableKioskModeUseCase.call();
      isKioskActive.value = false;
      if (ok) {
        AppLogger.debug('Kiosk mode (Lock Task Mode) disabled');
      } else {
        AppLogger.warning('Kiosk mode disable reported not-supported');
      }
    } catch (e) {
      AppLogger.error('Failed to disable kiosk mode', error: e);
    }
  }

  /// Subscribes to native service-state changes (push path).
  ///
  /// Updates [state.serviceStatus] and [state.isDemoRunning] whenever the
  /// foreground service emits a `running` or `stopped` event — including the
  /// background lock-trigger flow where no Flutter call is involved.
  void _subscribeServiceState() {
    _serviceStateSub = _watchServiceStateUseCase.call().listen(
      (status) {
        AppLogger.debug('Service state stream emitted: $status');
        _update(
          serviceStatus: status,
          isDemoRunning: status == ServiceStatus.running,
        );
      },
      onError: (Object e) =>
          AppLogger.error('Service state stream error', error: e),
    );
  }

  /// Subscribes to native lock-result events (push path).
  ///
  /// Updates [state.lockResult] and [state.lastLockTime] whenever the native
  /// lock engine reports an outcome — whether triggered manually via
  /// [lockNow] or automatically by the background countdown timer.
  void _subscribeLockResult() {
    _lockResultSub = _watchLockResultUseCase.call().listen(
      (result) {
        AppLogger.debug('Lock result stream emitted: $result');
        _update(
          lockResult: result,
          lastLockTime: result == LockResult.success
              ? DateTime.now().toUtc().toIso8601String()
              : state.value.lastLockTime,
        );
      },
      onError: (Object e) =>
          AppLogger.error('Lock result stream error', error: e),
    );
  }

  /// Subscribes to native lockout-state events (push path).
  ///
  /// Updates [state.isLockoutActive] whenever the native LockoutManager opens
  /// or closes the post-lock lockout window. The window opens on a successful
  /// lock (manual or background) and elapses after the configured duration.
  void _subscribeLockoutState() {
    _lockoutStateSub = _watchLockoutStateUseCase.call().listen(
      (active) {
        AppLogger.debug('Lockout state stream emitted: $active');
        _update(isLockoutActive: active);
      },
      onError: (Object e) =>
          AppLogger.error('Lockout state stream error', error: e),
    );
  }

  /// Subscribes to native uninstall-block change events (push path).
  ///
  /// Updates [state.isUninstallBlocked] whenever the native
  /// `UninstallBlockController` applies or removes the restriction — whether
  /// toggled manually via [toggleUninstallBlock] or altered outside the app.
  void _subscribeUninstallBlock() {
    _uninstallBlockSub = _watchUninstallBlockUseCase.call().listen(
      (blocked) {
        AppLogger.debug('Uninstall block stream emitted: $blocked');
        _update(isUninstallBlocked: blocked);
      },
      onError: (Object e) =>
          AppLogger.error('Uninstall block stream error', error: e),
    );
  }

  /// Re-checks the device-admin permission and updates state.
  Future<void> refreshPermission() async {
    final PermissionStatus status = await _checkPermissionUseCase.call();
    _update(permissionStatus: status);
  }

  /// Pulls the current uninstall-block capability and state from native
  /// (pull path) so the toggle is seeded correctly on startup.
  Future<void> refreshUninstallBlock() async {
    final bool supported = await _isUninstallBlockSupportedUseCase.call();
    final bool blocked = await _isUninstallBlockedUseCase.call();
    _update(isUninstallBlockSupported: supported, isUninstallBlocked: blocked);
  }

  /// Toggles the uninstall-block restriction.
  ///
  /// When turning the block on, the user cannot uninstall the app in any way
  /// (the native `DISALLOW_UNINSTALL_APPS` user restriction is applied). When
  /// turning it off, the restriction is removed and the user may uninstall
  /// normally. On an unsupported device the toggle is disabled by the UI.
  Future<void> toggleUninstallBlock() async {
    if (isBusy.value) return;
    if (!state.value.isUninstallBlockSupported) {
      AppLogger.warning('Uninstall block not supported on this device');
      return;
    }
    isBusy.value = true;
    try {
      final bool next = !state.value.isUninstallBlocked;
      final bool applied = await _setUninstallBlockedUseCase.call(next);
      // The push stream will reconcile the final state; optimistically reflect
      // the intended toggle only when the native call reports success.
      if (applied) {
        _update(isUninstallBlocked: next);
      }
    } finally {
      isBusy.value = false;
    }
  }

  /// Starts the demo: starts the background service and marks the demo
  /// as running. The service timer will trigger the lock after the
  /// predefined delay.
  Future<void> startDemo() async {
    if (isBusy.value) return;
    isBusy.value = true;
    try {
      final bool ok = await _startServiceUseCase.call();
      _update(
        serviceStatus: ok ? ServiceStatus.running : ServiceStatus.stopped,
        isDemoRunning: ok,
      );
    } finally {
      isBusy.value = false;
    }
  }

  /// Stops the demo: stops the background service.
  Future<void> stopDemo() async {
    if (isBusy.value) return;
    isBusy.value = true;
    try {
      await _stopServiceUseCase.call();
      _update(serviceStatus: ServiceStatus.stopped, isDemoRunning: false);
    } finally {
      isBusy.value = false;
    }
  }

  /// Sends an immediate lock command (manual trigger).
  Future<void> lockNow() async {
    if (isBusy.value) return;
    isBusy.value = true;
    try {
      final LockResult result = await _lockNowUseCase.call();
      _update(
        lockResult: result,
        lastLockTime: result == LockResult.success
            ? DateTime.now().toUtc().toIso8601String()
            : state.value.lastLockTime,
      );
    } finally {
      isBusy.value = false;
    }
  }

  /// Updates the user-selected lock duration (minutes).
  void selectLockMinutes(int minutes) {
    selectedLockMinutes.value = minutes;
    AppLogger.debug('Timed-lock duration set to $minutes min');
  }

  /// Locks the device for the user-selected duration.
  ///
  /// During that window any unlock attempt is immediately re-locked by the
  /// native LockoutManager. The lockout-state push stream will flip
  /// [AppState.isLockoutActive] to `true` and back to `false` when the
  /// window elapses.
  Future<void> lockForDuration() async {
    if (isBusy.value) return;
    isBusy.value = true;
    try {
      final int durationMs = selectedLockMinutes.value * 60 * 1000;
      final LockResult result = await _lockForDurationUseCase.call(durationMs);
      _update(
        lockResult: result,
        lastLockTime: result == LockResult.success
            ? DateTime.now().toUtc().toIso8601String()
            : state.value.lastLockTime,
      );
    } finally {
      isBusy.value = false;
    }
  }

  void _update({
    PermissionStatus? permissionStatus,
    ServiceStatus? serviceStatus,
    LockResult? lockResult,
    bool? isDemoRunning,
    bool? isLockoutActive,
    bool? isUninstallBlocked,
    bool? isUninstallBlockSupported,
    String? lastLockTime,
  }) {
    state.value = state.value.copyWith(
      permissionStatus: permissionStatus,
      serviceStatus: serviceStatus,
      lockResult: lockResult,
      isDemoRunning: isDemoRunning,
      isLockoutActive: isLockoutActive,
      isUninstallBlocked: isUninstallBlocked,
      isUninstallBlockSupported: isUninstallBlockSupported,
      lastLockTime: lastLockTime,
    );
    AppLogger.debug('Status state isDemoRunning: ${state.value.isDemoRunning}');
    AppLogger.debug(
      'Status state isLockoutActive: ${state.value.isLockoutActive}',
    );
    AppLogger.debug(
      'Status state isUninstallBlockSupported: ${state.value.isUninstallBlockSupported}',
    );
    AppLogger.debug(
      'Status state isUninstallBlocked: ${state.value.isUninstallBlocked}',
    );
    AppLogger.debug('Status state lastLockTime: ${state.value.lastLockTime}');
    AppLogger.debug('Status state lockResult: ${state.value.lockResult}');
    AppLogger.debug(
      'Status state permissionStatus: ${state.value.permissionStatus}',
    );
    AppLogger.debug('Status state serviceStatus: ${state.value.serviceStatus}');
  }

  /// Human-readable label for the lock-trigger delay.
  String get lockDelayLabel => '${AppConstants.lockTriggerDelaySeconds}s';

  @override
  void onClose() {
    _serviceStateSub?.cancel();
    _lockResultSub?.cancel();
    _lockoutStateSub?.cancel();
    _uninstallBlockSub?.cancel();
    // Release the Lock Task Mode pin so the system navigation buttons behave
    // normally once the Status screen is no longer visible.
    _disableKioskModeUseCase.call().catchError((Object e) {
      AppLogger.error('Failed to disable kiosk mode on close', error: e);
      return false;
    });
    AppLogger.debug('StatusController closed');
    super.onClose();
  }
}
