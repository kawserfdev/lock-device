import 'lock_result.dart';
import 'permission_status.dart';
import 'service_status.dart';

export 'lock_result.dart';
export 'permission_status.dart';
export 'service_status.dart';

/// Aggregate snapshot of the demo's runtime state.
///
/// Combines the individual status enums into a single value object that the
/// Status screen renders. Keeping it immutable and aggregated means the
/// controller can publish one [Rx] value instead of several.
class AppState {
  /// Constructs an immutable app-state snapshot.
  const AppState({
    required this.permissionStatus,
    required this.serviceStatus,
    required this.lockResult,
    required this.isDemoRunning,
    this.isLockoutActive = false,
    this.lastLockTime,
    this.isUninstallBlocked = false,
    this.isUninstallBlockSupported = false,
  });

  /// Current device-admin permission status.
  final PermissionStatus permissionStatus;

  /// Current background service status.
  final ServiceStatus serviceStatus;

  /// Result of the most recent lock command.
  final LockResult lockResult;

  /// Whether the demo is currently running.
  final bool isDemoRunning;

  /// Whether the post-lock lockout window is active.
  ///
  /// When `true`, unlock attempts are immediately re-locked by the native
  /// LockoutManager for the configured duration.
  final bool isLockoutActive;

  /// Whether the uninstall-block restriction is currently in force.
  ///
  /// When `true`, the user cannot uninstall the app in any way (the native
  /// `DISALLOW_UNINSTALL_APPS` user restriction is applied). Only meaningful
  /// when [isUninstallBlockSupported] is `true`.
  final bool isUninstallBlocked;

  /// Whether true uninstall blocking is supported on this device.
  ///
  /// Requires Device Owner or Profile Owner. When `false`, the restriction
  /// cannot be set and the UI reports the feature as unavailable.
  final bool isUninstallBlockSupported;

  /// ISO timestamp of the last successful lock, if any.
  final String? lastLockTime;

  /// Returns a copy with the provided fields replaced.
  AppState copyWith({
    PermissionStatus? permissionStatus,
    ServiceStatus? serviceStatus,
    LockResult? lockResult,
    bool? isDemoRunning,
    bool? isLockoutActive,
    bool? isUninstallBlocked,
    bool? isUninstallBlockSupported,
    String? lastLockTime,
  }) {
    return AppState(
      permissionStatus: permissionStatus ?? this.permissionStatus,
      serviceStatus: serviceStatus ?? this.serviceStatus,
      lockResult: lockResult ?? this.lockResult,
      isDemoRunning: isDemoRunning ?? this.isDemoRunning,
      isLockoutActive: isLockoutActive ?? this.isLockoutActive,
      isUninstallBlocked: isUninstallBlocked ?? this.isUninstallBlocked,
      isUninstallBlockSupported:
          isUninstallBlockSupported ?? this.isUninstallBlockSupported,
      lastLockTime: lastLockTime ?? this.lastLockTime,
    );
  }
}
