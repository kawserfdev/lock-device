/// Base class for all application errors.
///
/// Using a sealed failure hierarchy lets the presentation layer pattern-match
/// on failure types and render appropriate user-facing messages, while keeping
/// the domain layer free of Flutter/GetX dependencies.
sealed class AppError {
  /// Human-readable message describing the failure.
  final String message;

  /// Optional technical detail for diagnostics (logged, not shown to user).
  final String? detail;

  const AppError({required this.message, this.detail});
}

/// Failure originating from the platform channel (native side).
class PlatformError extends AppError {
  /// Native error code, if provided.
  final String? code;

  /// Constructs a platform error.
  const PlatformError({
    required super.message,
    super.detail,
    this.code,
  });
}

/// Failure caused by missing device-admin permission.
class PermissionError extends AppError {
  /// Constructs a permission error.
  const PermissionError({
    super.message = 'Device administrator permission is not granted.',
    super.detail,
  });
}

/// Failure caused by the device already being locked.
class AlreadyLockedError extends AppError {
  /// Constructs an already-locked error.
  const AlreadyLockedError({
    super.message = 'Device is already locked.',
    super.detail,
  });
}

/// Generic unexpected failure not covered by the specific types above.
class UnknownError extends AppError {
  /// Constructs an unknown error.
  const UnknownError({
    super.message = 'An unexpected error occurred.',
    super.detail,
  });
}
