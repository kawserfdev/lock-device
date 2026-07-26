import 'package:get/get.dart';

import '../../../domain/usecases/check_permission_usecase.dart';
import '../../../domain/usecases/get_user_usecase.dart';
import '../../../domain/usecases/is_uninstall_block_supported_usecase.dart';
import '../../../domain/usecases/is_uninstall_blocked_usecase.dart';
import '../../../domain/usecases/lock_for_duration_usecase.dart';
import '../../../domain/usecases/lock_now_usecase.dart';
import '../../../domain/usecases/set_uninstall_blocked_usecase.dart';
import '../../../domain/usecases/start_service_usecase.dart';
import '../../../domain/usecases/stop_service_usecase.dart';
import '../../../domain/usecases/update_user_lock_status_usecase.dart';
import '../../../domain/usecases/watch_lock_result_usecase.dart';
import '../../../domain/usecases/watch_lockout_state_usecase.dart';
import '../../../domain/usecases/watch_service_state_usecase.dart';
import '../../../domain/usecases/watch_uninstall_block_usecase.dart';
import '../controllers/status_controller.dart';

/// GetX binding for the Status screen.
///
/// Wires the Status controller with its use-case dependencies including user
/// and lock status settings management.
class StatusBinding extends Bindings {
  @override
  void dependencies() {
    Get.lazyPut<GetUserUseCase>(
      () => GetUserUseCase(Get.find()),
    );
    Get.lazyPut<UpdateUserLockStatusUseCase>(
      () => UpdateUserLockStatusUseCase(Get.find()),
    );
    Get.lazyPut<CheckPermissionUseCase>(
      () => CheckPermissionUseCase(Get.find()),
    );
    Get.lazyPut<StartServiceUseCase>(
      () => StartServiceUseCase(Get.find()),
    );
    Get.lazyPut<StopServiceUseCase>(
      () => StopServiceUseCase(Get.find()),
    );
    Get.lazyPut<LockNowUseCase>(
      () => LockNowUseCase(Get.find()),
    );
    Get.lazyPut<LockForDurationUseCase>(
      () => LockForDurationUseCase(Get.find()),
    );
    Get.lazyPut<SetUninstallBlockedUseCase>(
      () => SetUninstallBlockedUseCase(Get.find()),
    );
    Get.lazyPut<IsUninstallBlockedUseCase>(
      () => IsUninstallBlockedUseCase(Get.find()),
    );
    Get.lazyPut<IsUninstallBlockSupportedUseCase>(
      () => IsUninstallBlockSupportedUseCase(Get.find()),
    );
    Get.lazyPut<WatchServiceStateUseCase>(
      () => WatchServiceStateUseCase(Get.find()),
    );
    Get.lazyPut<WatchLockResultUseCase>(
      () => WatchLockResultUseCase(Get.find()),
    );
    Get.lazyPut<WatchLockoutStateUseCase>(
      () => WatchLockoutStateUseCase(Get.find()),
    );
    Get.lazyPut<WatchUninstallBlockUseCase>(
      () => WatchUninstallBlockUseCase(Get.find()),
    );
    Get.lazyPut<StatusController>(
      () => StatusController(
        getUserUseCase: Get.find(),
        updateUserLockStatusUseCase: Get.find(),
        checkPermissionUseCase: Get.find(),
        startServiceUseCase: Get.find(),
        stopServiceUseCase: Get.find(),
        lockNowUseCase: Get.find(),
        lockForDurationUseCase: Get.find(),
        watchServiceStateUseCase: Get.find(),
        watchLockResultUseCase: Get.find(),
        watchLockoutStateUseCase: Get.find(),
        setUninstallBlockedUseCase: Get.find(),
        isUninstallBlockedUseCase: Get.find(),
        isUninstallBlockSupportedUseCase: Get.find(),
        watchUninstallBlockUseCase: Get.find(),
      ),
    );
  }
}

