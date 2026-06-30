import 'package:get/get.dart';

import '../status/bindings/status_binding.dart';
import '../status/views/status_view.dart';
import '../welcome/bindings/welcome_binding.dart';
import '../welcome/views/welcome_view.dart';

/// Centralised GetX route table.
///
/// Each route pairs a path with its view and binding. Bindings perform
/// dependency injection lazily — controllers and use cases are created when
/// the route is entered and disposed when it is left.
class AppRoutes {
  AppRoutes._();

  /// Welcome screen route path.
  static const String welcome = '/welcome';

  /// Status screen route path.
  static const String status = '/status';

  /// The full list of application pages.
  static final List<GetPage<dynamic>> pages = <GetPage<dynamic>>[
    GetPage<void>(
      name: welcome,
      page: () => const WelcomeView(),
      binding: WelcomeBinding(),
    ),
    GetPage<void>(
      name: status,
      page: () => const StatusView(),
      binding: StatusBinding(),
    ),
  ];
}
