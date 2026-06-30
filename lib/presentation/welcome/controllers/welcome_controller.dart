import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:get/get.dart';

import '../../../core/constants/app_constants.dart';
import '../../../core/utils/app_logger.dart';
import '../../../domain/entities/app_state.dart';
import '../../../domain/usecases/check_permission_usecase.dart';
import '../../../domain/usecases/check_provisioning_allowed_usecase.dart';
import '../../../domain/usecases/request_permission_usecase.dart';
import '../../../domain/usecases/watch_permission_usecase.dart';

/// ViewModel for the Welcome screen.
///
/// Holds the Device Owner permission status as reactive state and exposes
/// actions to check and request permission. This controller contains no UI
/// code — it only manages state and delegates to use cases.
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
  })  : _checkPermissionUseCase = checkPermissionUseCase,
        _requestPermissionUseCase = requestPermissionUseCase,
        _watchPermissionUseCase = watchPermissionUseCase,
        _checkProvisioningAllowedUseCase = checkProvisioningAllowedUseCase;

  final CheckPermissionUseCase _checkPermissionUseCase;
  final RequestPermissionUseCase _requestPermissionUseCase;
  final WatchPermissionUseCase _watchPermissionUseCase;
  final CheckProvisioningAllowedUseCase _checkProvisioningAllowedUseCase;

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

  StreamSubscription<PermissionStatus>? _permissionSub;

  @override
  void onInit() {
    super.onInit();
    WidgetsBinding.instance.addObserver(this);
    _refreshPermission();
    _refreshProvisioningAllowed();
    _permissionSub = _watchPermissionUseCase.call().listen(
      (status) {
        AppLogger.debug('Permission stream emitted: $status');
        permissionStatus.value = status;
      },
      onError: (Object e) =>
          AppLogger.error('Permission stream error', error: e),
    );
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Re-check on resume: the user may have just acted on the system
    // provisioning screen. This is the pull-based half of the dual-confirmation
    // pattern.
    if (state == AppLifecycleState.resumed) {
      AppLogger.debug('App resumed — re-checking permission');
      _refreshPermission();
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

  /// Whether the user may proceed past the Welcome screen.
  bool get canProceed => permissionStatus.value == PermissionStatus.granted;

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
    WidgetsBinding.instance.removeObserver(this);
    AppLogger.debug('WelcomeController closed');
    super.onClose();
  }
}
