import '../repositories/uninstall_repository.dart';

/// Use case: query whether true uninstall blocking is supported on this device.
///
/// True blocking requires Device Owner or Profile Owner. On a non-provisioned
/// device this returns `false` and the UI reports the feature as unavailable.
class IsUninstallBlockSupportedUseCase {
  /// Constructs the use case with its repository dependency.
  IsUninstallBlockSupportedUseCase(this._repository);

  final UninstallRepository _repository;

  /// Executes the use case, returning whether the feature is supported.
  Future<bool> call() => _repository.isUninstallBlockSupported();
}
