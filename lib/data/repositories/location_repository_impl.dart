import 'dart:async';

import '../../core/utils/app_logger.dart';
import '../datasources/native_data_source.dart';
import '../../domain/repositories/location_repository.dart';

/// Implementation of [LocationRepository] delegating to [NativeDataSource].
class LocationRepositoryImpl implements LocationRepository {
  /// Constructs the location repository.
  LocationRepositoryImpl(this._nativeDataSource);

  final NativeDataSource _nativeDataSource;

  @override
  Future<bool> isLocationServiceEnabled() async {
    try {
      final bool enabled = await _nativeDataSource.checkLocationEnabled();
      AppLogger.debug('Location service enabled: $enabled');
      return enabled;
    } catch (e, st) {
      AppLogger.error(
        'isLocationServiceEnabled failed',
        error: e,
        stackTrace: st,
      );
      return false;
    }
  }

  @override
  Stream<bool> get locationServiceStatus {
    // Periodic poll to check location status every second,
    // yielding distinct status updates to the Welcome screen.
    late StreamController<bool> streamController;
    Timer? timer;

    streamController = StreamController<bool>.broadcast(
      onListen: () {
        timer = Timer.periodic(const Duration(seconds: 1), (Timer t) async {
          final bool enabled = await isLocationServiceEnabled();
          if (!streamController.isClosed) {
            streamController.add(enabled);
          }
        });
      },
      onCancel: () {
        timer?.cancel();
      },
    );

    return streamController.stream.distinct();
  }

  @override
  Future<bool> openLocationSettings() async {
    try {
      final bool opened = await _nativeDataSource.openLocationSettings();
      AppLogger.debug('openLocationSettings result: $opened');
      return opened;
    } catch (e, st) {
      AppLogger.error('openLocationSettings failed', error: e, stackTrace: st);
      return false;
    }
  }
}
