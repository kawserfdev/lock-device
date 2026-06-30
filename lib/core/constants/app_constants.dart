/// Application-wide constant values.
///
/// Centralises magic numbers and string literals so they are defined once
/// and referenced everywhere. Keeping them here avoids scattered hard-coded
/// values across the layers.
class AppConstants {
  AppConstants._();

  /// Application display name.
  static const String appName = 'App Lock';

  /// Default duration (in seconds) the background service waits before
  /// triggering the lock. Static demo value — no backend involved.
  static const int lockTriggerDelaySeconds = 30;

  /// GetX storage container name used by `get_storage`.
  static const String storageContainer = 'app_lock_storage';

  /// Animation duration for UI transitions.
  static const Duration defaultAnimationDuration = Duration(milliseconds: 300);

  /// Default page transition duration.
  static const Duration pageTransitionDuration = Duration(milliseconds: 250);

  /// Minimum supported Android API level for the device-admin lock feature.
  static const int minAndroidSdk = 21;

  /// Android application id (must match `applicationId` in build.gradle.kts).
  ///
  /// Used to build the `adb shell dpm set-device-owner` command shown to the
  /// user when in-app provisioning is not available.
  static const String androidApplicationId = 'com.example.app_lock';

  /// Fully-qualified DeviceAdminReceiver component used by `dpm`.
  ///
  /// Mirrors `AppLockDeviceAdminReceiver` on the Kotlin side. Combined with
  /// [androidApplicationId] this forms the argument to
  /// `adb shell dpm set-device-owner`.
  static const String deviceAdminReceiverComponent =
      'com.example.app_lock.admin.AppLockDeviceAdminReceiver';
}
