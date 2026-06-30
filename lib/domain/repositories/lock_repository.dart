import '../entities/app_state.dart';

/// Abstract port for the native lock engine.
///
/// Implemented by the data layer. Encapsulates the `lockNow` command and its
/// typed outcomes so the domain never touches platform channels directly.
abstract class LockRepository {
  /// Sends the lock command to the native lock engine.
  ///
  /// Returns the typed [LockResult] describing the outcome.
  Future<LockResult> lockNow();

  /// Stream of lock-result events pushed from native.
  Stream<LockResult> get lockResults;

  /// Stream of lockout-state events pushed from native.
  ///
  /// Emits `true` when the post-lock lockout window opens (unlock attempts will
  /// be re-locked) and `false` when it elapses.
  Stream<bool> get lockoutStateChanges;
}
