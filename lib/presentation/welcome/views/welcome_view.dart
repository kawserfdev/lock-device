import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:get/get.dart';

import '../../../core/constants/app_constants.dart';
import '../../../domain/entities/app_state.dart';
import '../controllers/welcome_controller.dart';

/// Welcome screen.
///
/// Explains why Device Owner permission is required and lets the user
/// provision it before proceeding. The view is stateless — all state lives in
/// [WelcomeController] and is observed via `Obx`.
///
/// Device Owner cannot always be granted via an in-app button: the
/// managed-provisioning intent only succeeds on a factory-fresh device with no
/// user accounts. When that path is unavailable, the view shows the
/// `adb shell dpm set-device-owner` command instead, which works in both debug
/// and release builds.
///
/// In addition to Device Owner permission, the Continue button is gated on the
/// device location (GPS) service being enabled. The GPS state is observed
/// reactively via the `geolocator` `ServiceStatus` stream, so the button
/// becomes active the instant the user turns location on in system Settings.
class WelcomeView extends GetView<WelcomeController> {
  /// Constructs the welcome view.
  const WelcomeView({super.key});

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text(AppConstants.appName)),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: 16),
              _AppLogo(theme: theme),
              const SizedBox(height: 32),
              Text(
                'Welcome to ${AppConstants.appName}',
                style: theme.textTheme.headlineSmall,
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 16),
              Text(
                'This demo shows how an installment locker can remotely lock '
                'an Android device when a payment is overdue, and block the '
                'app from being uninstalled.',
                style: theme.textTheme.bodyLarge,
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 24),
              _WarningCard(theme: theme),
              const SizedBox(height: 24),
              const _PermissionStatusCard(),
              const SizedBox(height: 16),
              const _ProvisioningGuidance(),
              const SizedBox(height: 32),
              const _EnableButton(),
              const SizedBox(height: 12),
              const _LocationStatusCard(),
              const SizedBox(height: 12),
              const _ContinueButton(),
            ],
          ),
        ),
      ),
    );
  }
}

class _AppLogo extends StatelessWidget {
  const _AppLogo({required this.theme});
  final ThemeData theme;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 96,
      height: 96,
      decoration: BoxDecoration(
        color: theme.colorScheme.primaryContainer,
        shape: BoxShape.circle,
      ),
      child: Icon(
        Icons.lock_outline,
        size: 48,
        color: theme.colorScheme.onPrimaryContainer,
      ),
    );
  }
}

class _WarningCard extends StatelessWidget {
  const _WarningCard({required this.theme});
  final ThemeData theme;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(Icons.warning_amber_rounded, color: theme.colorScheme.error),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                'Device Owner permission is required. It allows this app to '
                'lock the screen and to block its own uninstallation via the '
                'DISALLOW_UNINSTALL_APPS restriction. Device Owner is stronger '
                'than a plain Device Admin and is the only role that can set '
                'user restrictions.',
                style: theme.textTheme.bodyMedium,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _PermissionStatusCard extends StatelessWidget {
  const _PermissionStatusCard();

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final WelcomeController controller = Get.find();
    return Obx(() {
      final PermissionStatus status = controller.permissionStatus.value;
      return Card(
        child: ListTile(
          leading: Icon(
            status == PermissionStatus.granted
                ? Icons.check_circle
                : Icons.cancel,
            color: status == PermissionStatus.granted
                ? theme.colorScheme.primary
                : theme.colorScheme.error,
          ),
          title: const Text('Device Owner Permission'),
          subtitle: Text(_label(status)),
        ),
      );
    });
  }

  String _label(PermissionStatus status) => switch (status) {
    PermissionStatus.granted => 'Granted — you may continue.',
    PermissionStatus.denied =>
      'Not granted. Provision the app as Device Owner below.',
    PermissionStatus.unknown => 'Checking…',
  };
}

/// Shows the appropriate provisioning guidance depending on whether in-app
/// managed-provisioning is allowed.
///
/// - When [WelcomeController.isProvisioningAllowed] is `true`, the in-app
///   "Enable Device Owner" button (below) can launch the system
///   managed-provisioning flow.
/// - When it is `false`, the device already has user accounts and the in-app
///   intent cannot succeed. The user must run the `adb shell dpm
///   set-device-owner` command instead, which works in both debug and release
///   builds.
class _ProvisioningGuidance extends StatelessWidget {
  const _ProvisioningGuidance();

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final WelcomeController controller = Get.find();
    return Obx(() {
      // Once permission is granted, no guidance is needed.
      if (controller.permissionStatus.value == PermissionStatus.granted) {
        return const SizedBox.shrink();
      }
      final bool allowed = controller.isProvisioningAllowed.value;
      return allowed
          ? _InAppProvisioningHint(theme: theme)
          : _AdbProvisioningCard(controller: controller, theme: theme);
    });
  }
}

class _InAppProvisioningHint extends StatelessWidget {
  const _InAppProvisioningHint({required this.theme});
  final ThemeData theme;

  @override
  Widget build(BuildContext context) {
    return Card(
      color: theme.colorScheme.secondaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(
              Icons.info_outline,
              color: theme.colorScheme.onSecondaryContainer,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                'This device supports in-app provisioning. Tap "Enable '
                'Device Owner" below to start the system setup flow.',
                style: theme.textTheme.bodyMedium,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _AdbProvisioningCard extends StatelessWidget {
  const _AdbProvisioningCard({required this.controller, required this.theme});
  final WelcomeController controller;
  final ThemeData theme;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(Icons.terminal, color: theme.colorScheme.primary),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    'In-app provisioning is not available on this device '
                    '(it already has user accounts). Make this app the Device '
                    'Owner by running this adb command from a computer:',
                    style: theme.textTheme.bodyMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            _CommandBox(command: controller.adbSetDeviceOwnerCommand),
            const SizedBox(height: 12),
            _PrerequisiteStep(
              theme: theme,
              number: '1',
              text:
                  'Remove ALL accounts on the device: Settings → '
                  'Passwords & accounts → select each account → Remove.',
            ),
            const SizedBox(height: 8),
            _PrerequisiteStep(
              theme: theme,
              number: '2',
              text:
                  'Run the adb command above from a computer with USB '
                  'debugging connected.',
            ),
            const SizedBox(height: 8),
            _PrerequisiteStep(
              theme: theme,
              number: '3',
              text: 'Return to this app and tap "Continue".',
            ),
            const SizedBox(height: 12),
            Text(
              'Works in both debug and release builds. The adb command will '
              'fail with "Not allowed to set the device owner" if any '
              'account remains on the device.',
              style: theme.textTheme.bodySmall,
            ),
          ],
        ),
      ),
    );
  }
}

class _PrerequisiteStep extends StatelessWidget {
  const _PrerequisiteStep({
    required this.theme,
    required this.number,
    required this.text,
  });
  final ThemeData theme;
  final String number;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 22,
          height: 22,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: theme.colorScheme.primaryContainer,
            shape: BoxShape.circle,
          ),
          child: Text(
            number,
            style: theme.textTheme.labelSmall?.copyWith(
              color: theme.colorScheme.onPrimaryContainer,
              fontWeight: FontWeight.bold,
            ),
          ),
        ),
        const SizedBox(width: 10),
        Expanded(child: Text(text, style: theme.textTheme.bodyMedium)),
      ],
    );
  }
}

class _CommandBox extends StatelessWidget {
  const _CommandBox({required this.command});
  final String command;

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    return Container(
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      child: Row(
        children: [
          Expanded(
            child: SelectableText(
              command,
              style: theme.textTheme.bodyMedium?.copyWith(
                fontFamily: 'monospace',
              ),
            ),
          ),
          IconButton(
            tooltip: 'Copy',
            icon: const Icon(Icons.copy, size: 20),
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: command));
              if (context.mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text('Command copied to clipboard'),
                    duration: Duration(seconds: 2),
                  ),
                );
              }
            },
          ),
        ],
      ),
    );
  }
}

class _EnableButton extends StatelessWidget {
  const _EnableButton();

  @override
  Widget build(BuildContext context) {
    final WelcomeController controller = Get.find();
    return Obx(() {
      // Hide the in-app button when provisioning is not allowed — the adb
      // path is the only option in that case.
      if (!controller.isProvisioningAllowed.value) {
        return const SizedBox.shrink();
      }
      return FilledButton.icon(
        onPressed: controller.isLoading.value
            ? null
            : () => controller.requestPermission(),
        icon: controller.isLoading.value
            ? const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : const Icon(Icons.admin_panel_settings),
        label: Text(
          controller.isLoading.value ? 'Requesting…' : 'Enable Device Owner',
        ),
      );
    });
  }
}

/// Card showing the live device location (GPS) service status.
///
/// Reflects [WelcomeController.isLocationEnabled] which is updated instantly
/// via the `geolocator` `ServiceStatus` stream. Gives the user a visible
/// hint that GPS must be on before they can continue, and offers a direct
/// shortcut to the system location settings screen.
class _LocationStatusCard extends StatelessWidget {
  const _LocationStatusCard();

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final WelcomeController controller = Get.find();
    return Obx(() {
      final bool enabled = controller.isLocationEnabled.value;
      return Card(
        child: ListTile(
          leading: Icon(
            enabled ? Icons.location_on : Icons.location_off,
            color: enabled
                ? theme.colorScheme.primary
                : theme.colorScheme.error,
          ),
          title: const Text('Device Location (GPS)'),
          subtitle: Text(
            enabled
                ? 'Enabled — you may continue.'
                : 'Enable location to continue.',
          ),
          trailing: enabled
              ? null
              : GestureDetector(
                  onTap: () => controller.openLocationSettings(),
                  child: const Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(Icons.settings, size: 18),
                      SizedBox(height: 6),
                      Text('Open Settings'),
                    ],
                  ),
                ),
        ),
      );
    });
  }
}

/// Continue button gated on **both** Device Owner permission and GPS.
///
/// Behaviour:
/// - Permission not granted → fully disabled (not tappable).
/// - Permission granted, GPS OFF → visually greyed but still tappable; tapping
///   shows a native-style dialog prompting the user to enable location (with a
///   button that opens the system location settings). The button is kept
///   tappable so the user gets actionable feedback instead of a dead control.
/// - Permission granted, GPS ON → active/coloured; tapping navigates to the
///   Status screen.
///
/// The GPS state is reactive ([WelcomeController.isLocationEnabled] is fed by
/// the `geolocator` `ServiceStatus` stream), so the button becomes active the
/// instant the user turns location on in system Settings — no manual refresh
/// needed.
class _ContinueButton extends StatelessWidget {
  const _ContinueButton();

  @override
  Widget build(BuildContext context) {
    final WelcomeController controller = Get.find();
    final ThemeData theme = Theme.of(context);
    return Obx(() {
      final bool permissionGranted =
          controller.permissionStatus.value == PermissionStatus.granted;
      final bool locationOn = controller.isLocationEnabled.value;

      // When permission is not granted the button is fully disabled (not
      // tappable) — the user must provision Device Owner first.
      if (!permissionGranted) {
        return FilledButton.tonalIcon(
          onPressed: null,
          icon: const Icon(Icons.arrow_forward),
          label: const Text('Continue'),
        );
      }

      // Permission is granted. The button is always tappable so we can show a
      // location prompt when GPS is off, but it is visually greyed to signal
      // that the user cannot proceed yet.
      return FilledButton.tonalIcon(
        onPressed: () {
          if (locationOn) {
            Get.toNamed<void>('/status');
          } else {
            _showLocationRequiredDialog(context, controller);
          }
        },
        style: locationOn
            ? null
            : FilledButton.styleFrom(
                foregroundColor: theme.disabledColor,
                backgroundColor: theme.colorScheme.surfaceContainerHighest,
              ),
        icon: Icon(locationOn ? Icons.arrow_forward : Icons.location_off),
        label: Text(locationOn ? 'Continue' : 'Location is OFF'),
      );
    });
  }

  /// Native-style alert dialog shown when the user taps Continue while GPS is
  /// off. Offers a direct shortcut to the system location settings screen.
  void _showLocationRequiredDialog(
    BuildContext context,
    WelcomeController controller,
  ) {
    showDialog<void>(
      context: context,
      builder: (BuildContext dialogContext) => AlertDialog(
        icon: const Icon(Icons.location_off),
        title: const Text('Location Required'),
        content: const Text(
          'Device location (GPS) must be turned ON to continue. '
          'This keeps the device trackable while the installment locker '
          'protection is active.',
        ),
        actions: <Widget>[
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () {
              Navigator.of(dialogContext).pop();
              controller.openLocationSettings();
            },
            child: const Text('Open Settings'),
          ),
        ],
      ),
    );
  }
}
