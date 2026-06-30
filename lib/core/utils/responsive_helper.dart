import 'package:flutter/widgets.dart';
import 'package:flutter_screenutil/flutter_screenutil.dart';

/// Helper that initialises [ScreenUtil] for responsive sizing.
///
/// Must be called once after the app's first frame is laid out, so that
/// ScreenUtil can read the actual screen dimensions. Screens then use the
/// `.w`, `.h`, `.sp` extensions for density-scaled sizing.
class ResponsiveHelper {
  ResponsiveHelper._();

  /// Initialises ScreenUtil with the design-time draft size.
  ///
  /// [designSize] is the logical size of the device the UI was designed on.
  /// All `.w`/`.h`/`.sp` values are scaled relative to it.
  static void init(
    BuildContext context, {
    Size designSize = const Size(390, 844),
  }) {
    ScreenUtil.init(context, designSize: designSize);
  }
}
