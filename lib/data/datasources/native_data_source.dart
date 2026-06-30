import 'dart:async';

import '../../core/constants/channel_constants.dart';
import '../../core/platform/app_event_channel.dart';
import '../../core/platform/app_method_channel.dart';
import '../../core/utils/app_logger.dart';
import '../../domain/entities/app_state.dart';

/// Data source that talks to the native side via platform channels.
///
/// This is the only place in the data layer that touches `AppMethodChannel`
/// and `AppEventChannel`. Repository implementations depend on this data
/// source, translating raw native payloads into domain entities.
class NativeDataSource {
  /// Constructs the native data source with its channel dependencies.
  NativeDataSource({
    required AppMethodChannel methodChannel,
    required AppEventChannel eventChannel,
  })  : _methodChannel = methodChannel,
        _eventChannel = eventChannel;

  final AppMethodChannel _methodChannel;
  final AppEventChannel _eventChannel;

  // ── Permission ───────────────────────────────────────────────────────────

  /// Asks native whether Device Owner / Profile Owner permission is active.
  Future<bool> checkPermission() {
    return _methodChannel.invokeBoolMethod(
      ChannelConstants.methodCheckPermission,
    );
  }

  /// Asks native to launch the managed-provisioning flow to
  /// become Device Owner.
  Future<bool> requestPermission() {
    return _methodChannel.invokeBoolMethod(
      ChannelConstants.methodRequestPermission,
    );
  }

  /// Asks native whether in-app Device Owner provisioning is allowed.
  ///
  /// Returns `true` only on a device with no user accounts (factory-fresh),
  /// where the managed-provisioning intent can succeed. When `false`, the user
  /// must use `adb shell dpm set-device-owner` instead.
  Future<bool> isProvisioningAllowed() {
    return _methodChannel.invokeBoolMethod(
      ChannelConstants.methodIsProvisioningAllowed,
    );
  }

  // ── Lock ──────────────────────────────────────────────────────────────────

  /// Sends the lock command to the native lock engine.
  Future<bool> lockNow() {
    return _methodChannel.invokeBoolMethod(
      ChannelConstants.methodLockNow,
    );
  }

  /// Sends the lock command with a custom lockout duration.
  ///
  /// The device locks immediately and any unlock attempt within [durationMs]
  /// is countered by an immediate re-lock. After [durationMs] elapses the
  /// user may unlock normally.
  Future<bool> lockForDuration(int durationMs) {
    return _methodChannel.invokeBoolMethod(
      ChannelConstants.methodLockForDuration,
      args: <String, dynamic>{
        ChannelConstants.keyDurationMs: durationMs,
      },
    );
  }

  // ── Service ───────────────────────────────────────────────────────────────

  /// Asks native to start the foreground service.
  Future<bool> startService() {
    return _methodChannel.invokeBoolMethod(
      ChannelConstants.methodStartService,
    );
  }

  /// Asks native to stop the foreground service.
  Future<bool> stopService() {
    return _methodChannel.invokeBoolMethod(
      ChannelConstants.methodStopService,
    );
  }

  /// Asks native whether the service is currently running.
  Future<bool> isServiceRunning() {
    return _methodChannel.invokeBoolMethod(
      ChannelConstants.methodIsServiceRunning,
    );
  }

  // ── Uninstall block ───────────────────────────────────────────────────────

  /// Asks native to apply (`true`) or remove (`false`) the uninstall-block
  /// restriction.
  ///
  /// Returns `true` when the restriction was actually applied/removed; `false`
  /// when the app is not a Device/Profile Owner (the restriction cannot be set)
  /// or the call failed.
  Future<bool> setUninstallBlocked(bool blocked) {
    return _methodChannel.invokeBoolMethod(
      ChannelConstants.methodSetUninstallBlocked,
      args: <String, dynamic>{
        ChannelConstants.keyBlocked: blocked,
      },
    );
  }

  /// Asks native whether the uninstall-block restriction is currently active.
  Future<bool> isUninstallBlocked() {
    return _methodChannel.invokeBoolMethod(
      ChannelConstants.methodIsUninstallBlocked,
    );
  }

  /// Asks native whether true uninstall blocking is supported on this device
  /// (requires Device Owner or Profile Owner).
  Future<bool> isUninstallBlockSupported() {
    return _methodChannel.invokeBoolMethod(
      ChannelConstants.methodIsUninstallBlockSupported,
    );
  }

  // ── Event streams ─────────────────────────────────────────────────────────

  /// Stream of permission-state change events from native.
  Stream<PermissionStatus> get permissionChanges {
    return _eventChannel.events
        .where((e) => e['type'] == ChannelConstants.eventPermissionChanged)
        .map(_mapPermission);
  }

  /// Stream of lock-result events from native.
  Stream<LockResult> get lockResults {
    return _eventChannel.events
        .where((e) => e['type'] == ChannelConstants.eventLockResult)
        .map(_mapLockResult);
  }

  /// Stream of service-state change events from native.
  Stream<ServiceStatus> get serviceStateChanges {
    return _eventChannel.events
        .where((e) => e['type'] == ChannelConstants.eventServiceStateChanged)
        .map(_mapServiceStatus);
  }

  /// Stream of lockout-state change events from native.
  ///
  /// Emits `true` when the post-lock lockout window opens (unlock attempts will
  /// be re-locked) and `false` when it elapses.
  Stream<bool> get lockoutStateChanges {
    return _eventChannel.events
        .where((e) => e['type'] == ChannelConstants.eventLockoutStateChanged)
        .map(_mapLockoutState);
  }

  /// Stream of uninstall-block change events from native.
  ///
  /// Emits `true` when the restriction is applied and `false` when it is
  /// removed (or reported as not in force on an unsupported device).
  Stream<bool> get uninstallBlockChanges {
    return _eventChannel.events
        .where((e) => e['type'] == ChannelConstants.eventUninstallBlockChanged)
        .map(_mapUninstallBlock);
  }

  PermissionStatus _mapPermission(Map<String, dynamic> event) {
    final bool? granted = event['granted'] as bool?;
    return switch (granted) {
      true => PermissionStatus.granted,
      false => PermissionStatus.denied,
      null => PermissionStatus.unknown,
    };
  }

  LockResult _mapLockResult(Map<String, dynamic> event) {
    final String? result = event['result'] as String?;
    return switch (result) {
      'success' => LockResult.success,
      'already_locked' => LockResult.alreadyLocked,
      'permission_missing' => LockResult.permissionMissing,
      _ => LockResult.failed,
    };
  }

  ServiceStatus _mapServiceStatus(Map<String, dynamic> event) {
    final String? status = event['status'] as String?;
    return switch (status) {
      'running' => ServiceStatus.running,
      'transitioning' => ServiceStatus.transitioning,
      _ => ServiceStatus.stopped,
    };
  }

  bool _mapLockoutState(Map<String, dynamic> event) {
    final bool? active = event['active'] as bool?;
    return active ?? false;
  }

  bool _mapUninstallBlock(Map<String, dynamic> event) {
    final bool? blocked = event['blocked'] as bool?;
    return blocked ?? false;
  }

  /// Releases the event channel resources.
  void dispose() {
    _eventChannel.dispose();
    AppLogger.debug('NativeDataSource disposed');
  }
}
