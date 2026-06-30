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
    return Scaffold(
      appBar: AppBar(title: const Text('Demo Status')),
      body: const SafeArea(
        child: SingleChildScrollView(
          padding: EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _PermissionTile(),
              SizedBox(height: 8),
              _ServiceTile(),
              SizedBox(height: 8),
              _LockTile(),
              SizedBox(height: 8),
              _LockoutTile(),
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
    );
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
              ? 'Active (1 min)'
              : 'Inactive',
        ));
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
                    ? 'Blocked — app cannot be uninstalled'
                    : 'Enabled — app can be uninstalled',
          ),
          trailing: Switch(
            value: blocked,
            // Disable while busy or when the device is not a Device/Profile
            // Owner (the restriction cannot be set).
            onChanged: (controller.isBusy.value || !supported)
                ? null
                : (_) => controller.toggleUninstallBlock(),
          ),
        ),
      );
    });
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
