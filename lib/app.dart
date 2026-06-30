import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../core/constants/app_constants.dart';
import '../core/theme/app_theme.dart';
import '../presentation/routes/app_routes.dart';

/// Root application widget.
///
/// Configures the GetMaterialApp with the Material 3 theme, the route table,
/// and the initial route. Keeping this separate from `main.dart` allows the
/// bootstrap logic (storage init, DI) to run before the widget tree is built.
class App extends StatelessWidget {
  /// Constructs the root app widget.
  const App({super.key});

  @override
  Widget build(BuildContext context) {
    return GetMaterialApp(
      title: AppConstants.appName,
      debugShowCheckedModeBanner: false,
      theme: AppTheme.light,
      darkTheme: AppTheme.dark,
      themeMode: ThemeMode.system,
      initialRoute: AppRoutes.welcome,
      getPages: AppRoutes.pages,
      builder: (BuildContext context, Widget? child) {
        // Initialise responsive sizing once the first frame is available.
        return MediaQuery.withClampedTextScaling(
          minScaleFactor: 0.9,
          maxScaleFactor: 1.2,
          child: child ?? const SizedBox.shrink(),
        );
      },
    );
  }
}
