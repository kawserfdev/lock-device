import '../entities/user_model.dart';
import '../repositories/user_repository.dart';

/// Use case to fetch the current user profile and lock status.
class GetUserUseCase {
  /// Constructs the use case with its repository dependency.
  const GetUserUseCase(this._userRepository);

  final UserRepository _userRepository;

  /// Executes the use case to return the [UserModel].
  Future<UserModel> call() => _userRepository.getUser();
}
