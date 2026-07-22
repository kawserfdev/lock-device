import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../../domain/entities/app_state.dart';
import '../controllers/status_controller.dart';

/// Status screen.
///
/// Displays the demo's runtime state and exposes four actions: start demo,
/// stop demo, lock now, and check permission. The view is stateless — all
/// state lives in [StatusController] and is observed via `Obx`.
class StatusView extends GetView<StatusController> {
  /// Constructs the status view.
  const StatusView({super.key});

  @override
  Widget build(BuildContext context) {
    // Lock the Back button while kiosk (Lock Task Mode) is active. The Home /
    // Recents buttons are disabled natively by the kiosk mode. A "Release &
    // Exit" affordance in the AppBar lets the user leave on demand.
    return Obx(() {
      final bool kioskActive = controller.isKioskActive.value;
      return PopScope(
        canPop: !kioskActive,
        child: Scaffold(
          appBar: AppBar(
            title: const Text('Demo Status'),
            actions: <Widget>[
              if (kioskActive)
                IconButton(
                  icon: const Icon(Icons.lock_open),
                  tooltip: 'Release & Exit',
                  onPressed: () async {
                    await controller.exitKiosk();
                    if (Get.context != null && Get.context!.mounted) {
                      Get.back<void>();
                    }
                  },
                ),
            ],
          ),
          body: const SafeArea(
            child: SingleChildScrollView(
              padding: EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _KioskTile(),
                  SizedBox(height: 8),
                  _PermissionTile(),
                  SizedBox(height: 8),
                  _ServiceTile(),
                  SizedBox(height: 8),
                  _LockTile(),
                  SizedBox(height: 8),
                  _LockoutTile(),
                  SizedBox(height: 8),
                  _TimedLockTile(),
                  SizedBox(height: 8),
                  _DemoTile(),
                  SizedBox(height: 8),
                  _UninstallBlockTile(),
                  SizedBox(height: 24),
                  _ActionButtons(),
                ],
              ),
            ),
          ),
        ),
      );
    });
  }
}

/// Tile showing whether kiosk (Lock Task Mode) is active.
///
/// When active, the Back / Home / Recents buttons are disabled and the user
/// must use the AppBar "Release & Exit" affordance to leave the screen.
class _KioskTile extends StatelessWidget {
  const _KioskTile();

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final StatusController controller = Get.find();
    return Obx(() => _StatusCard(
          theme: theme,
          icon: Icons.pin,
          title: 'Kiosk Mode',
          value: controller.isKioskActive.value
              ? 'Active — Back / Home / Recents disabled'
              : 'Inactive — navigation enabled',
        ));
  }
}

class _PermissionTile extends StatelessWidget {
  const _PermissionTile();

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final StatusController controller = Get.find();
    return Obx(() => _StatusCard(
          theme: theme,
          icon: Icons.admin_panel_settings,
          title: 'Permission Status',
          value: _permissionLabel(controller.state.value.permissionStatus),
        ));
  }

  String _permissionLabel(PermissionStatus s) => switch (s) {
        PermissionStatus.granted => 'Granted',
        PermissionStatus.denied => 'Denied',
        PermissionStatus.unknown => 'Unknown',
      };
}

class _ServiceTile extends StatelessWidget {
  const _ServiceTile();

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final StatusController controller = Get.find();
    return Obx(() => _StatusCard(
          theme: theme,
          icon: Icons.play_circle_outline,
          title: 'Background Service',
          value: _serviceLabel(controller.state.value.serviceStatus),
        ));
  }

  String _serviceLabel(ServiceStatus s) => switch (s) {
        ServiceStatus.running => 'Running',
        ServiceStatus.stopped => 'Stopped',
        ServiceStatus.transitioning => 'Transitioning…',
      };
}

class _LockTile extends StatelessWidget {
  const _LockTile();

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final StatusController controller = Get.find();
    return Obx(() => _StatusCard(
          theme: theme,
          icon: Icons.lock,
          title: 'Last Lock Result',
          value: _lockLabel(controller.state.value.lockResult),
          subtitle: controller.state.value.lastLockTime ?? 'Never',
        ));
  }

  String _lockLabel(LockResult r) => switch (r) {
        LockResult.success => 'Success',
        LockResult.alreadyLocked => 'Already locked',
        LockResult.permissionMissing => 'Permission missing',
        LockResult.failed => 'Failed',
      };
}

class _LockoutTile extends StatelessWidget {
  const _LockoutTile();

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final StatusController controller = Get.find();
    return Obx(() => _StatusCard(
          theme: theme,
          icon: Icons.lock_clock,
          title: 'Lockout Window',
          value: controller.state.value.isLockoutActive
              ? 'Active'
              : 'Inactive',
        ));
  }
}

class _TimedLockTile extends StatelessWidget {
  const _TimedLockTile();

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final StatusController controller = Get.find();
    return Obx(() {
      final int minutes = controller.selectedLockMinutes.value;
      return Card(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: Icon(Icons.timer, color: theme.colorScheme.primary),
                title: const Text('Timed Lock'),
                subtitle: Text(
                  'Lock for $minutes minute${minutes == 1 ? '' : 's'}',
                ),
                trailing: TextButton.icon(
                  onPressed: () => _showDurationPicker(context, controller),
                  icon: const Icon(Icons.edit),
                  label: const Text('Change'),
                ),
              ),
              FilledButton.icon(
                onPressed: controller.isBusy.value
                    ? null
                    : () => controller.lockForDuration(),
                icon: const Icon(Icons.lock_clock),
                label: Text('Lock for $minutes min'),
              ),
            ],
          ),
        ),
      );
    });
  }

  /// Shows a dialog of preset durations and applies the selection.
  Future<void> _showDurationPicker(
    BuildContext context,
    StatusController controller,
  ) async {
    final int? selected = await showDialog<int>(
      context: context,
      builder: (BuildContext ctx) => SimpleDialog(
        title: const Text('Lock duration'),
        children: controller.lockDurationPresets
            .map(
              (int minutes) => SimpleDialogOption(
                onPressed: () => Navigator.of(ctx).pop(minutes),
                child: Text('$minutes minute${minutes == 1 ? '' : 's'}'),
              ),
            )
            .toList(),
      ),
    );
    if (selected != null) {
      controller.selectLockMinutes(selected);
    }
  }
}

class _DemoTile extends StatelessWidget {
  const _DemoTile();

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final StatusController controller = Get.find();
    return Obx(() => _StatusCard(
          theme: theme,
          icon: Icons.science_outlined,
          title: 'Demo Status',
          value: controller.state.value.isDemoRunning
              ? 'Running (lock in ${controller.lockDelayLabel})'
              : 'Idle',
        ));
  }
}

class _UninstallBlockTile extends StatelessWidget {
  const _UninstallBlockTile();

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final StatusController controller = Get.find();
    return Obx(() {
      final AppState s = controller.state.value;
      final bool supported = s.isUninstallBlockSupported;
      final bool blocked = s.isUninstallBlocked;
      return Card(
        child: ListTile(
          leading: Icon(
            Icons.delete_forever,
            color: theme.colorScheme.primary,
          ),
          title: const Text('Uninstall Block'),
          subtitle: Text(
            !supported
                ? 'Unavailable (needs Device Owner)'
                : blocked
                    ? 'Blocked — uninstall, factory reset & '
                        'reset app preferences disabled'
                    : 'Enabled — app can be uninstalled',
          ),
          trailing: Switch(
            value: blocked,
            // Disable while busy or when the device is not a Device/Profile
            // Owner (the restriction cannot be set).
            onChanged: (controller.isBusy.value || !supported)
                ? null
                : (bool value) async {
                    if (!value) {
                      // Turning OFF is destructive — confirm first.
                      final bool? confirmed =
                          await _showClearOwnerDialog(context);
                      if (confirmed != true) return;
                    }
                    await controller.toggleUninstallBlock();
                  },
          ),
        ),
      );
    });
  }

  /// Shows a confirmation dialog before the destructive toggle-OFF.
  ///
  /// Turning the block off clears the Device Owner role (one-way) and
  /// launches the system uninstall dialog. The user must acknowledge the
  /// consequences before proceeding.
  Future<bool?> _showClearOwnerDialog(BuildContext context) {
    return showDialog<bool>(
      context: context,
      builder: (BuildContext ctx) => AlertDialog(
        title: const Text('Remove Uninstall Block?'),
        content: const Text(
          'This will clear the Device Owner permission (one-way) '
          'and launch the system uninstall dialog.\n\n'
          '• The app will become uninstallable.\n'
          '• The screen-lock feature will be disabled until you '
          're-provision via adb.\n'
          '• You cannot undo this from inside the app.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Continue'),
          ),
        ],
      ),
    );
  }
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({
    required this.theme,
    required this.icon,
    required this.title,
    required this.value,
    this.subtitle,
  });

  final ThemeData theme;
  final IconData icon;
  final String title;
  final String value;
  final String? subtitle;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: ListTile(
        leading: Icon(icon, color: theme.colorScheme.primary),
        title: Text(title),
        subtitle: Text(subtitle ?? value),
        trailing: Text(
          subtitle != null ? value : '',
          style: theme.textTheme.titleSmall,
        ),
      ),
    );
  }
}

class _ActionButtons extends StatelessWidget {
  const _ActionButtons();

  @override
  Widget build(BuildContext context) {
    final StatusController controller = Get.find();
    return Obx(() => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            FilledButton.icon(
              onPressed: controller.isBusy.value
                  ? null
                  : () => controller.startDemo(),
              icon: const Icon(Icons.play_arrow),
              label: const Text('Start Demo'),
            ),
            const SizedBox(height: 8),
            FilledButton.tonalIcon(
              onPressed: controller.isBusy.value
                  ? null
                  : () => controller.stopDemo(),
              icon: const Icon(Icons.stop),
              label: const Text('Stop Demo'),
            ),
            const SizedBox(height: 8),
            OutlinedButton.icon(
              onPressed: controller.isBusy.value
                  ? null
                  : () => controller.lockNow(),
              icon: const Icon(Icons.lock),
              label: const Text('Lock Now'),
            ),
            const SizedBox(height: 8),
            TextButton.icon(
              onPressed: () => controller.refreshPermission(),
              icon: const Icon(Icons.refresh),
              label: const Text('Check Permission'),
            ),
            const SizedBox(height: 8),
            TextButton.icon(
              onPressed: controller.isBusy.value
                  ? null
                  : () => controller.refreshUninstallBlock(),
              icon: const Icon(Icons.shield),
              label: const Text('Refresh Uninstall Block'),
            ),
          ],
        ));
  }
}
