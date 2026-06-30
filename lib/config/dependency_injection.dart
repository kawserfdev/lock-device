import 'package:get/get.dart';
import 'package:get_storage/get_storage.dart';

import '../core/constants/app_constants.dart';
import '../core/platform/app_event_channel.dart';
import '../core/platform/app_method_channel.dart';
import '../data/datasources/local_data_source.dart';
import '../data/datasources/native_data_source.dart';
import '../data/repositories/background_service_repository_impl.dart';
import '../data/repositories/lock_repository_impl.dart';
import '../data/repositories/permission_repository_impl.dart';
import '../data/repositories/uninstall_repository_impl.dart';
import '../domain/repositories/background_service_repository.dart';
import '../domain/repositories/lock_repository.dart';
import '../domain/repositories/permission_repository.dart';
import '../domain/repositories/uninstall_repository.dart';

/// Centralised dependency-injection wiring.
///
/// Registers the platform channels, data sources, and repository
/// implementations into the GetX container. Use cases and controllers are
/// registered lazily by their respective bindings. This class is the single
/// composition root for the data/domain layers.
class DependencyInjection {
  DependencyInjection._();

  /// Registers all core/data/domain dependencies.
  ///
  /// Must be called once during app initialisation, after `GetStorage.init`.
  static void init() {
    // ── Platform channels (singletons) ──────────────────────────────────────
    Get.put<AppMethodChannel>(AppMethodChannel(), permanent: true);
    Get.put<AppEventChannel>(AppEventChannel(), permanent: true);

    // ── Storage container ────────────────────────────────────────────────────
    final GetStorage storage = GetStorage(AppConstants.storageContainer);
    Get.put<GetStorage>(storage, permanent: true);

    // ── Data sources ─────────────────────────────────────────────────────────
    Get.put<NativeDataSource>(
      NativeDataSource(
        methodChannel: Get.find<AppMethodChannel>(),
        eventChannel: Get.find<AppEventChannel>(),
      ),
      permanent: true,
    );
    Get.put<LocalDataSource>(
      LocalDataSource(Get.find<GetStorage>()),
      permanent: true,
    );

    // ── Repositories (bind abstractions to implementations) ──────────────────
    Get.put<PermissionRepository>(
      PermissionRepositoryImpl(
        nativeDataSource: Get.find<NativeDataSource>(),
        localDataSource: Get.find<LocalDataSource>(),
      ),
      permanent: true,
    );
    Get.put<LockRepository>(
      LockRepositoryImpl(
        nativeDataSource: Get.find<NativeDataSource>(),
        localDataSource: Get.find<LocalDataSource>(),
      ),
      permanent: true,
    );
    Get.put<BackgroundServiceRepository>(
      BackgroundServiceRepositoryImpl(Get.find<NativeDataSource>()),
      permanent: true,
    );
    Get.put<UninstallRepository>(
      UninstallRepositoryImpl(nativeDataSource: Get.find<NativeDataSource>()),
      permanent: true,
    );
  }
}
