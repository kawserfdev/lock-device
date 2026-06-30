import 'package:flutter/foundation.dart';
import 'package:logger/logger.dart';

/// Thin wrapper around the `logger` package.
///
/// The rest of the app imports `AppLogger` only — never the underlying
/// `Logger` — so the logging implementation can be swapped without touching
/// call sites. In debug builds logs are printed to console; in release builds
/// logging is silenced to avoid leaking diagnostics.
class AppLogger {
  AppLogger._();

  /// Singleton logger instance configured with a pretty printer.
  static final Logger _logger = Logger(
    printer: PrettyPrinter(
      methodCount: 0,
      errorMethodCount: 8,
      colors: true,
      printEmojis: true,
      dateTimeFormat: DateTimeFormat.onlyTimeAndSinceStart,
    ),
    level: kDebugMode ? Level.trace : Level.off,
  );

  /// Verbose diagnostic information.
  static void trace(String message) => _logger.t(message);

  /// Debug-level information useful during development.
  static void debug(String message) => _logger.d(message);

  /// Informational message highlighting normal application flow.
  static void info(String message) => _logger.i(message);

  /// Warning indicating a recoverable problem.
  static void warning(String message) => _logger.w(message);

  /// Error with optional stack trace and error object.
  static void error(
    String message, {
    Object? error,
    StackTrace? stackTrace,
  }) =>
      _logger.e(message, error: error, stackTrace: stackTrace);
}
