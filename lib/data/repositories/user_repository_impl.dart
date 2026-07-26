import '../../core/utils/app_logger.dart';
import '../../domain/entities/user_model.dart';
import '../../domain/repositories/user_repository.dart';
import '../datasources/local_data_source.dart';

/// Concrete implementation of [UserRepository] backed by [LocalDataSource].
class UserRepositoryImpl implements UserRepository {
  /// Constructs the repository with its local data source.
  UserRepositoryImpl(this._localDataSource);

  final LocalDataSource _localDataSource;

  @override
  Future<UserModel> getUser() async {
    try {
      final String? jsonStr = _localDataSource.userModelJson;
      if (jsonStr != null && jsonStr.isNotEmpty) {
        return UserModel.fromJson(jsonStr);
      }
    } catch (e) {
      AppLogger.error(
        'Failed to parse saved user model, using default',
        error: e,
      );
    }
    return UserModel.defaultUser;
  }

  @override
  Future<void> saveUser(UserModel user) async {
    try {
      await _localDataSource.setUserModelJson(user.toJson());
      AppLogger.debug('Saved UserModel successfully');
    } catch (e) {
      AppLogger.error('Failed to save user model', error: e);
    }
  }
}
