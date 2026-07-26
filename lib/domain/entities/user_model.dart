import 'dart:convert';
import 'user_lock_status.dart';

export 'user_lock_status.dart';

/// Entity representing user information and lock configuration settings.
class UserModel {
  /// Constructs a user model.
  const UserModel({
    required this.id,
    required this.name,
    required this.email,
    this.lockStatus = const UserLockStatus(),
  });

  /// Unique user ID.
  final String id;

  /// User display name.
  final String name;

  /// User email or contact identifier.
  final String email;

  /// Associated lock status settings module.
  final UserLockStatus lockStatus;

  /// Default demo user profile.
  static const UserModel defaultUser = UserModel(
    id: 'user_001',
    name: 'Demo User',
    email: 'user@example.com',
    lockStatus: UserLockStatus(
      isGpsDeactiveLockActive: false,
      isLockTimeActive: false,
      lockTime: '22:00',
    ),
  );

  /// Returns a copy with the provided fields replaced.
  UserModel copyWith({
    String? id,
    String? name,
    String? email,
    UserLockStatus? lockStatus,
  }) {
    return UserModel(
      id: id ?? this.id,
      name: name ?? this.name,
      email: email ?? this.email,
      lockStatus: lockStatus ?? this.lockStatus,
    );
  }

  /// Converts object to Map representation.
  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'id': id,
      'name': name,
      'email': email,
      'lockStatus': lockStatus.toMap(),
    };
  }

  /// Factory constructor to create [UserModel] from Map.
  factory UserModel.fromMap(Map<String, dynamic> map) {
    return UserModel(
      id: map['id'] as String? ?? 'user_001',
      name: map['name'] as String? ?? 'Demo User',
      email: map['email'] as String? ?? 'user@example.com',
      lockStatus: map['lockStatus'] != null
          ? UserLockStatus.fromMap(map['lockStatus'] as Map<String, dynamic>)
          : const UserLockStatus(),
    );
  }

  /// Serializes object to JSON string.
  String toJson() => json.encode(toMap());

  /// Deserializes JSON string into [UserModel].
  factory UserModel.fromJson(String source) =>
      UserModel.fromMap(json.decode(source) as Map<String, dynamic>);
}
