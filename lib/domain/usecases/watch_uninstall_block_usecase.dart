import '../repositories/uninstall_repository.dart';

/// Use case: observe uninstall-block change events pushed from native.
///
/// Returns the live [Stream] of `bool` events emitted by the native
/// [UninstallBlockController]. Emits `true` when the restriction is applied
/// and `false` when it is removed (or reported as not in force on an
/// unsupported device).
///
/// This is the push-based half of the dual-path state synchronisation
/// (architecture review §16.3). The Status UI subscribes so the toggle
/// reflects the live restriction state the moment it changes — whether
/// toggled manually or altered outside the app.
class WatchUninstallBlockUseCase {
  /// Constructs the use case with its repository dependency.
  WatchUninstallBlockUseCase(this._repository);

  final UninstallRepository _repository;

  /// Executes the use case, returning the uninstall-block stream.
  Stream<bool> call() => _repository.uninstallBlockChanges;
}
