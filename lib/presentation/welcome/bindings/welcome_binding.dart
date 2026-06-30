import 'package:get/get.dart';

import '../../../domain/usecases/check_permission_usecase.dart';
import '../../../domain/usecases/check_provisioning_allowed_usecase.dart';
import '../../../domain/usecases/request_permission_usecase.dart';
import '../../../domain/usecases/watch_permission_usecase.dart';
import '../controllers/welcome_controller.dart';

/// GetX binding for the Welcome screen.
///
/// Wires the Welcome controller with its use-case dependencies. Bindings
/// keep DI configuration separate from the view, so the view stays focused
/// on presentation.
class WelcomeBinding extends Bindings {
  @override
  void dependencies() {
    // Use cases are registered lazily — they are created on first access.
    Get.lazyPut<CheckPermissionUseCase>(
      () => CheckPermissionUseCase(Get.find()),
    );
    Get.lazyPut<RequestPermissionUseCase>(
      () => RequestPermissionUseCase(Get.find()),
    );
    Get.lazyPut<WatchPermissionUseCase>(
      () => WatchPermissionUseCase(Get.find()),
    );
    Get.lazyPut<CheckProvisioningAllowedUseCase>(
      () => CheckProvisioningAllowedUseCase(Get.find()),
    );
    Get.lazyPut<WelcomeController>(
      () => WelcomeController(
        checkPermissionUseCase: Get.find(),
        requestPermissionUseCase: Get.find(),
        watchPermissionUseCase: Get.find(),
        checkProvisioningAllowedUseCase: Get.find(),
      ),
    );
  }
}
