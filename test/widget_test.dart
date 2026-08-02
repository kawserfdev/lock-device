import 'package:flutter_test/flutter_test.dart';
import 'package:get/get.dart';

import 'package:app_lock/app.dart';
import 'package:app_lock/domain/entities/app_state.dart';
import 'package:app_lock/domain/repositories/kiosk_repository.dart';
import 'package:app_lock/domain/repositories/location_repository.dart';
import 'package:app_lock/domain/repositories/permission_repository.dart';
import 'package:app_lock/domain/repositories/uninstall_repository.dart';

class FakePermissionRepository implements PermissionRepository {
  @override
  Future<PermissionStatus> checkPermission() async => PermissionStatus.unknown;

  @override
  Future<PermissionStatus> requestPermission() async =>
      PermissionStatus.unknown;

  @override
  Future<bool> isProvisioningAllowed() async => true;

  @override
  Stream<PermissionStatus> get permissionChanges => const Stream.empty();
}

class FakeUninstallRepository implements UninstallRepository {
  @override
  Future<bool> setUninstallBlocked(bool blocked) async => true;

  @override
  Future<bool> isUninstallBlocked() async => false;

  @override
  Future<bool> isUninstallBlockSupported() async => true;

  @override
  Stream<bool> get uninstallBlockChanges => const Stream.empty();
}

class FakeLocationRepository implements LocationRepository {
  @override
  Future<bool> isLocationServiceEnabled() async => true;

  @override
  Stream<bool> get locationServiceStatus => const Stream.empty();

  @override
  Future<bool> openLocationSettings() async => true;
}

class FakeKioskRepository implements KioskRepository {
  @override
  Future<bool> enableKioskMode() async => true;

  @override
  Future<bool> disableKioskMode() async => true;
}

void main() {
  setUp(() {
    Get.reset();
    Get.put<PermissionRepository>(FakePermissionRepository());
    Get.put<UninstallRepository>(FakeUninstallRepository());
    Get.put<LocationRepository>(FakeLocationRepository());
    Get.put<KioskRepository>(FakeKioskRepository());
  });

  testWidgets('App root builds', (WidgetTester tester) async {
    // Build the App widget and trigger a frame.
    await tester.pumpWidget(const App());

    // The Welcome screen AppBar title should be present.
    expect(find.text('App Lock'), findsOneWidget);
  });
}
