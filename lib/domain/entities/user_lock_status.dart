import 'dart:convert';

/// Value object representing lock configuration status for a user.
class UserLockStatus {
  /// Constructs a user lock status object.
  const UserLockStatus({
    this.isGpsDeactiveLockActive = false,
    this.isLockTimeActive = false,
    this.lockTime = '22:00',
  });

  /// Whether device locking is active when GPS is deactivated.
  final bool isGpsDeactiveLockActive;

  /// Whether device locking is active during scheduled lock time.
  final bool isLockTimeActive;

  /// Scheduled lock time string (e.g. "22:00").
  final String lockTime;

  /// Returns a copy with the provided fields replaced.
  UserLockStatus copyWith({
    bool? isGpsDeactiveLockActive,
    bool? isLockTimeActive,
    String? lockTime,
  }) {
    return UserLockStatus(
      isGpsDeactiveLockActive:
          isGpsDeactiveLockActive ?? this.isGpsDeactiveLockActive,
      isLockTimeActive: isLockTimeActive ?? this.isLockTimeActive,
      lockTime: lockTime ?? this.lockTime,
    );
  }

  /// Converts object to Map representation.
  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'isGpsDeactiveLockActive': isGpsDeactiveLockActive,
      'isLockTimeActive': isLockTimeActive,
      'lockTime': lockTime,
    };
  }

  /// Factory constructor to create [UserLockStatus] from Map.
  factory UserLockStatus.fromMap(Map<String, dynamic> map) {
    return UserLockStatus(
      isGpsDeactiveLockActive: map['isGpsDeactiveLockActive'] as bool? ?? false,
      isLockTimeActive: map['isLockTimeActive'] as bool? ?? false,
      lockTime: map['lockTime'] as String? ?? '22:00',
    );
  }

  /// Serializes object to JSON string.
  String toJson() => json.encode(toMap());

  /// Deserializes JSON string into [UserLockStatus].
  factory UserLockStatus.fromJson(String source) =>
      UserLockStatus.fromMap(json.decode(source) as Map<String, dynamic>);
}
