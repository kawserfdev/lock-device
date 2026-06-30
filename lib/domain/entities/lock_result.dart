/// Represents the outcome of a lock command.
///
/// Pure domain enum. The native lock engine reports success or a typed
/// failure reason; the data layer maps it into this enum.
enum LockResult {
  /// Device was successfully locked.
  success,

  /// Device was already locked — no action taken.
  alreadyLocked,

  /// Permission was missing when the lock was attempted.
  permissionMissing,

  /// An unexpected error occurred during locking.
  failed,
}
