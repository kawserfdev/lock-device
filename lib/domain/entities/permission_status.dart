/// Represents the device-administrator permission state.
///
/// Pure domain enum — no Flutter/GetX/Android imports. The data layer maps
/// native permission outcomes into this enum; the presentation layer renders
/// it.
enum PermissionStatus {
  /// Permission has not yet been requested.
  unknown,

  /// Permission is granted and active.
  granted,

  /// Permission was requested but denied/revoked.
  denied,
}
