/// Abstract port for the uninstall-block restriction.
///
/// Implemented by the data layer. The domain toggles and observes the
/// uninstall-block restriction through this port without knowing how the
/// native side realises it (Device Owner restriction + secondary warning
/// layer).
abstract class UninstallRepository {
  /// Applies (`true`) or removes (`false`) the uninstall-block restriction.
  ///
  /// Returns `true` when the restriction was actually applied/removed; `false`
  /// when the app is not a Device/Profile Owner (the restriction cannot be set)
  /// or the call failed.
  Future<bool> setUninstallBlocked(bool blocked);

  /// Returns whether the uninstall-block restriction is currently active.
  Future<bool> isUninstallBlocked();

  /// Returns whether true uninstall blocking is supported on this device
  /// (requires Device Owner or Profile Owner).
  Future<bool> isUninstallBlockSupported();

  /// Stream of uninstall-block change events pushed from native.
  ///
  /// Emits `true` when the restriction is applied and `false` when it is
  /// removed (or reported as not in force on an unsupported device).
  Stream<bool> get uninstallBlockChanges;
}
