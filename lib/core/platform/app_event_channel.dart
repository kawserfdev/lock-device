import 'dart:async';

import 'package:flutter/services.dart';

import '../constants/channel_constants.dart';
import '../errors/app_error.dart';
import '../utils/app_logger.dart';

/// Wrapper around the application's EventChannel.
///
/// Exposes native push-based status updates as a typed broadcast [Stream].
/// Data sources subscribe to this stream and forward domain-relevant events
/// to repositories/controllers. The raw `EventChannel` is never used outside
/// this class.
class AppEventChannel {
  /// Constructs the event channel wrapper.
  AppEventChannel()
      : _channel = const EventChannel(ChannelConstants.eventChannelName);

  final EventChannel _channel;

  Stream<Map<String, dynamic>>? _broadcast;

  /// Broadcast stream of native status events.
  ///
  /// Lazily created and shared across subscribers so multiple listeners can
  /// observe the same native event source without re-subscribing.
  Stream<Map<String, dynamic>> get events {
    _broadcast ??= _channel
        .receiveBroadcastStream()
        .map((dynamic event) {
          if (event is Map) {
            return Map<String, dynamic>.from(event);
          }
          return <String, dynamic>{};
        })
        .handleError((Object error) {
          AppLogger.error('EventChannel stream error', error: error);
          throw PlatformError(
            message: 'Event channel stream failed.',
            detail: error.toString(),
          );
        })
        .asBroadcastStream();
    return _broadcast!;
  }

  /// Releases the broadcast stream.
  void dispose() {
    _broadcast = null;
  }
}
