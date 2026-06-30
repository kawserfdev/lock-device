import 'package:flutter_test/flutter_test.dart';

import 'package:app_lock/app.dart';

// This is a basic widget test for the application root.
//
// It verifies that the root App widget builds without throwing. Full
// feature tests will be added alongside business logic in later steps.

void main() {
  testWidgets('App root builds', (WidgetTester tester) async {
    // Build the App widget and trigger a frame.
    await tester.pumpWidget(const App());

    // The Welcome screen AppBar title should be present.
    expect(find.text('App Lock'), findsOneWidget);
  });
}
