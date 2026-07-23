import 'dart:async';
import 'dart:developer';

import 'package:flutter/widgets.dart';
import 'package:get/get.dart';

import '../../../core/constants/app_constants.dart';
import '../../../core/utils/app_logger.dart';
import '../../../domain/entities/app_state.dart';
import '../../../domain/usecases/check_location_enabled_usecase.dart';
import '../../../domain/usecases/check_permission_usecase.dart';
import '../../../domain/usecases/check_provisioning_allowed_usecase.dart';
import '../../../domain/usecases/open_location_settings_usecase.dart';
import '../../../domain/usecases/request_permission_usecase.dart';
import '../../../domain/usecases/watch_location_status_usecase.dart';
import '../../../domain/usecases/watch_permission_usecase.dart';

/// ViewModel for the Welcome screen.
///
/// Holds the Device Owner permission status and the device location (GPS)
/// service status as reactive state, and exposes actions to check/request
/// permission and to open the system location settings. This controller
/// contains no UI code — it only manages state and delegates to use cases.
///
/// Permission flow (architecture review §5.1 — dual confirmation):
/// 1. On init, the current status is pulled via [CheckPermissionUseCase].
/// 2. The controller subscribes to [WatchPermissionUseCase], the push stream
///    fed by the native `DeviceAdminReceiver` callbacks. This updates the UI
///    the moment the user enables/disables admin — even while the system
///    screen is still on top.
/// 3. As a safety net, the controller observes [WidgetsBindingObserver]'s
///    `didChangeAppLifecycleState` and re-checks on resume. This catches the
///    case where the receiver callback is delayed or the user navigates back
///    without the event firing.
///
/// Location flow (mirrors the dual-confirmation pattern):
/// 1. On init, the current GPS status is pulled via
///    [CheckLocationEnabledUseCase].
/// 2. The controller subscribes to [WatchLocationStatusUseCase], the push
///    stream
///    fed by the `geolocator` plugin's `ServiceStatus` stream. This updates the
///    Continue button the instant the user toggles location on/off in Settings.
/// 3. On resume (after the user returns from the location settings screen) the
///    status is re-checked as a safety net.
///
/// Device Owner provisioning reality:
/// Device Owner cannot be granted via a simple in-app toggle on a device that
/// already has user accounts. The controller therefore also exposes
/// [isProvisioningAllowed] (whether the in-app managed-provisioning intent can
/// succeed) and [adbSetDeviceOwnerCommand] (the `dpm` command the user can run
/// via adb, which works in both debug and release builds).
class WelcomeController extends GetxController with WidgetsBindingObserver {
  /// Constructs the controller with its use-case dependencies.
  WelcomeController({
    required CheckPermissionUseCase checkPermissionUseCase,
    required RequestPermissionUseCase requestPermissionUseCase,
    required WatchPermissionUseCase watchPermissionUseCase,
    required CheckProvisioningAllowedUseCase checkProvisioningAllowedUseCase,
    required CheckLocationEnabledUseCase checkLocationEnabledUseCase,
    required WatchLocationStatusUseCase watchLocationStatusUseCase,
    required OpenLocationSettingsUseCase openLocationSettingsUseCase,
  })  : _checkPermissionUseCase = checkPermissionUseCase,
        _requestPermissionUseCase = requestPermissionUseCase,
        _watchPermissionUseCase = watchPermissionUseCase,
        _checkProvisioningAllowedUseCase = checkProvisioningAllowedUseCase,
        _checkLocationEnabledUseCase = checkLocationEnabledUseCase,
        _watchLocationStatusUseCase = watchLocationStatusUseCase,
        _openLocationSettingsUseCase = openLocationSettingsUseCase;

  final CheckPermissionUseCase _checkPermissionUseCase;
  final RequestPermissionUseCase _requestPermissionUseCase;
  final WatchPermissionUseCase _watchPermissionUseCase;
  final CheckProvisioningAllowedUseCase _checkProvisioningAllowedUseCase;
  final CheckLocationEnabledUseCase _checkLocationEnabledUseCase;
  final WatchLocationStatusUseCase _watchLocationStatusUseCase;
  final OpenLocationSettingsUseCase _openLocationSettingsUseCase;

  /// Reactive permission status displayed on the Welcome screen.
  final Rx<PermissionStatus> permissionStatus =
      PermissionStatus.unknown.obs;

  /// Whether a permission request is in flight.
  final RxBool isLoading = false.obs;

  /// Whether in-app Device Owner provisioning is allowed on this device.
  ///
  /// `true` only on a factory-fresh device with no user accounts. When `false`,
  /// the UI shows the `adb shell dpm set-device-owner` command instead of the
  /// in-app provisioning button.
  final RxBool isProvisioningAllowed = false.obs;

  /// Whether the device location (GPS) service is currently enabled.
  ///
  /// Drives the Continue button's enabled state: the user may only proceed
  /// when both Device Owner permission is granted **and** location is on.
  /// Updated instantly via the `geolocator` `ServiceStatus` stream.
  final RxBool isLocationEnabled = false.obs;

  StreamSubscription<PermissionStatus>? _permissionSub;
  StreamSubscription<bool>? _locationStatusSub;

  @override
  void onInit() {
    super.onInit();
    log("Command executed: $adbSetDeviceOwnerCommand");
    WidgetsBinding.instance.addObserver(this);
    _refreshPermission();
    _refreshProvisioningAllowed();
    _refreshLocationEnabled();
    _permissionSub = _watchPermissionUseCase.call().listen(
      (status) {
        AppLogger.debug('Permission stream emitted: $status');
        permissionStatus.value = status;
      },
      onError: (Object e) =>
          AppLogger.error('Permission stream error', error: e),
    );
    // Subscribe to the geolocator ServiceStatus stream so the Continue button
    // reacts the instant the user toggles location on/off in system Settings.
    _locationStatusSub = _watchLocationStatusUseCase.call().listen(
      (enabled) {
        AppLogger.debug('Location status stream emitted: $enabled');
        isLocationEnabled.value = enabled;
      },
      onError: (Object e) =>
          AppLogger.error('Location status stream error', error: e),
    );
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Re-check on resume: the user may have just acted on the system
    // provisioning screen or the location settings screen. This is the
    // pull-based half of the dual-confirmation pattern for both flows.
    if (state == AppLifecycleState.resumed) {
      AppLogger.debug('App resumed — re-checking permission & location');
      _refreshPermission();
      _refreshLocationEnabled();
    }
  }

  /// Re-checks the current permission status.
  Future<void> _refreshPermission() async {
    permissionStatus.value = await _checkPermissionUseCase.call();
  }

  /// Re-checks whether in-app provisioning is allowed.
  Future<void> _refreshProvisioningAllowed() async {
    isProvisioningAllowed.value =
        await _checkProvisioningAllowedUseCase.call();
  }

  /// Re-checks the current device location (GPS) service status.
  Future<void> _refreshLocationEnabled() async {
    isLocationEnabled.value = await _checkLocationEnabledUseCase.call();
  }

  /// Requests Device Owner permission via the managed-provisioning intent.
  ///
  /// Sets [isLoading] while the request is dispatched. The resulting status is
  /// `unknown` until the user acts — the actual outcome arrives via the
  /// permission stream or the resume re-check.
  Future<void> requestPermission() async {
    if (isLoading.value) return;
    isLoading.value = true;
    try {
      permissionStatus.value = await _requestPermissionUseCase.call();
    } finally {
      isLoading.value = false;
    }
  }

  /// Opens the system location settings screen so the user can turn GPS on.
  ///
  /// Called by the Continue button when the user taps it while location is off.
  /// Returns `true` when the settings screen was successfully launched.
  Future<bool> openLocationSettings() async {
    return _openLocationSettingsUseCase.call();
  }

  /// Whether the user may proceed past the Welcome screen.
  ///
  /// Requires **both** Device Owner permission to be granted **and** the
  /// device location (GPS) service to be enabled. The location gate ensures
  /// the device stays trackable once the Installment Locker's protections are
  /// activated on the next screen.
  bool get canProceed =>
      permissionStatus.value == PermissionStatus.granted &&
      isLocationEnabled.value;

  /// The `adb shell dpm set-device-owner` command the user can run to make
  /// this app the Device Owner.
  ///
  /// Works in both debug and release builds. Requires the device to have no
  /// user accounts (remove them in Settings → Accounts first).
  String get adbSetDeviceOwnerCommand =>
      'adb shell dpm set-device-owner '
      '${AppConstants.androidApplicationId}/'
      '${AppConstants.deviceAdminReceiverComponent}';

  @override
  void onClose() {
    _permissionSub?.cancel();
    _locationStatusSub?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    AppLogger.debug('WelcomeController closed');
    super.onClose();
  }
}
