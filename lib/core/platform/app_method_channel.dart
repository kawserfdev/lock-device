import 'package:flutter/services.dart';

import '../constants/channel_constants.dart';
import '../errors/app_error.dart';
import '../utils/app_logger.dart';

/// Wrapper around the application's MethodChannel.
///
/// Encapsulates the raw `MethodChannel` so the rest of the app interacts
/// with a typed, error-handling surface instead of raw platform calls.
/// Data sources in the data layer depend on this abstraction, never on
/// `MethodChannel` directly.
class AppMethodChannel {
  /// Constructs the channel wrapper.
  AppMethodChannel()
      : _channel = const MethodChannel(ChannelConstants.methodChannelName);

  final MethodChannel _channel;

  /// Invokes a native method expecting a [bool] result.
  ///
  /// Catches platform exceptions and converts them into [PlatformError] so
  /// callers can handle failures uniformly via the `Either`-style return.
  Future<bool> invokeBoolMethod(
  String method, {
  Map<String, dynamic>? args,
}) async {
  // 1. Log the incoming request and arguments
  AppLogger.debug('Calling MethodChannel: $method with args: $args');

  try {
    final dynamic result = await _channel.invokeMethod<dynamic>(
      method,
      args,
    );

    final bool value = result is bool ? result : false;

    // 2. Log non-boolean fallback behavior if native returns an unexpected type
    if (result is! bool) {
      AppLogger.warning(
        'MethodChannel $method returned non-bool value (${result.runtimeType}: $result). Defaulting to false.',
      );
    }

    // 3. Log successful response
    AppLogger.info('MethodChannel $method succeeded with result: $value');

    return value;
  } on PlatformException catch (e) {
    // 4. Log platform error details
    AppLogger.error(
      'MethodChannel bool call failed: $method',
      error: e,
      stackTrace: StackTrace.current,
    );
    throw PlatformError(
      message: e.message ?? 'Platform call failed',
      code: e.code,
      detail: e.details?.toString(),
    );
  } on MissingPluginException catch (e) {
    // 5. Log missing native implementation
    AppLogger.error(
      'Method not implemented on native side: $method',
      error: e,
    );
    throw PlatformError(
      message: 'Native method "$method" is not implemented.',
      detail: e.toString(),
    );
  }
}
  
  // Future<bool> invokeBoolMethod(
  //   String method, {
  //   Map<String, dynamic>? args,
  // }) async {
  //   try {
  //     final dynamic result = await _channel.invokeMethod<dynamic>(
  //       method,
  //       args,
  //     );
  //     return result is bool ? result : false;
  //   } on PlatformException catch (e) {
  //     AppLogger.error(
  //       'MethodChannel bool call failed: $method',
  //       error: e,
  //       stackTrace: StackTrace.current,
  //     );
  //     throw PlatformError(
  //       message: e.message ?? 'Platform call failed',
  //       code: e.code,
  //       detail: e.details?.toString(),
  //     );
  //   } on MissingPluginException catch (e) {
  //     AppLogger.error(
  //       'Method not implemented on native side: $method',
  //       error: e,
  //     );
  //     throw PlatformError(
  //       message: 'Native method "$method" is not implemented.',
  //       detail: e.toString(),
  //     );
  //   }
  // }

  /// Invokes a native method expecting a [String] result (nullable).
  Future<String?> invokeStringMethod(
    String method, {
    Map<String, dynamic>? args,
  }) async {
    try {
      final dynamic result = await _channel.invokeMethod<dynamic>(
        method,
        args,
      );
      return result is String ? result : null;
    } on PlatformException catch (e) {
      AppLogger.error(
        'MethodChannel string call failed: $method',
        error: e,
        stackTrace: StackTrace.current,
      );
      throw PlatformError(
        message: e.message ?? 'Platform call failed',
        code: e.code,
        detail: e.details?.toString(),
      );
    } on MissingPluginException catch (e) {
      AppLogger.error(
        'Method not implemented on native side: $method',
        error: e,
      );
      throw PlatformError(
        message: 'Native method "$method" is not implemented.',
        detail: e.toString(),
      );
    }
  }
}
