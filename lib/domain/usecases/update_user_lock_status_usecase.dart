import '../entities/user_model.dart';
import '../repositories/user_repository.dart';

/// Use case to update the user's lock status configuration.
class UpdateUserLockStatusUseCase {
  /// Constructs the use case with its repository dependency.
  const UpdateUserLockStatusUseCase(this._userRepository);

  final UserRepository _userRepository;

  /// Updates specific lock status flags and saves the user model.
  Future<UserModel> call({
    bool? isGpsDeactiveLockActive,
    bool? isLockTimeActive,
    String? lockTime,
  }) async {
    final UserModel currentUser = await _userRepository.getUser();
    final UserLockStatus updatedStatus = currentUser.lockStatus.copyWith(
      isGpsDeactiveLockActive: isGpsDeactiveLockActive,
      isLockTimeActive: isLockTimeActive,
      lockTime: lockTime,
    );
    final UserModel updatedUser = currentUser.copyWith(
      lockStatus: updatedStatus,
    );
    await _userRepository.saveUser(updatedUser);
    return updatedUser;
  }
}
