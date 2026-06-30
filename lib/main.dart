import 'package:flutter/material.dart';
import 'package:get_storage/get_storage.dart';

import 'app.dart';
import 'config/dependency_injection.dart';
import 'core/constants/app_constants.dart';
import 'core/utils/app_logger.dart';

/// Application entry point.
///
/// Performs bootstrap tasks that must complete before the UI is built:
///   1. Initialise the local storage container.
///   2. Register dependencies into the GetX container.
///   3. Run the root [App] widget.
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Initialise local storage before DI, since the local data source
  // depends on a ready GetStorage container.
  await GetStorage.init(AppConstants.storageContainer);

  // Register platform channels, data sources, and repositories.
  DependencyInjection.init();

  AppLogger.info('App bootstrap complete');

  runApp(const App());
}
