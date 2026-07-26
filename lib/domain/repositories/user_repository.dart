import '../entities/user_model.dart';

/// Contract for managing user profile and lock status settings.
abstract class UserRepository {
  /// Retrieves the current user model.
  Future<UserModel> getUser();

  /// Saves or updates the user model.
  Future<void> saveUser(UserModel user);
}
