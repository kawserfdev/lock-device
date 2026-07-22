/// Abstract port for the native kiosk-mode (Lock Task Mode / screen pinning)
/// engine.
///
/// Implemented by the data layer. Encapsulates the enable/disable commands so
/// the domain never touches platform channels directly. Used by the Status
/// ("App Lock Time counter") screen to disable the Home and Recents buttons
/// while it is visible; the Back gesture is intercepted on the Flutter side
/// via `PopScope`.
abstract class KioskRepository {
  /// Pins the host Activity via Android Lock Task Mode so the Home and
  /// Recents buttons are disabled.
  ///
  /// Returns `true` when the pin was started; `false` when the device is not a
  /// Device Owner (the Lock Task Mode allowlist cannot be set) or the call
  /// failed.
  Future<bool> enableKioskMode();

  /// Releases the Lock Task Mode pin applied by [enableKioskMode] and restores
  /// normal navigation.
  ///
  /// Returns `true` when the pin was stopped (or there was nothing to stop);
  /// `false` only when an unexpected error occurred.
  Future<bool> disableKioskMode();
}
