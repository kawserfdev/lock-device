import 'package:flutter/material.dart';

/// A reusable section header widget.
///
/// Renders a title with optional trailing action. Used to label groups of
/// content on the screens for visual hierarchy.
class SectionHeader extends StatelessWidget {
  /// Constructs a section header.
  const SectionHeader({
    required this.title,
    this.trailing,
    super.key,
  });

  /// The section title.
  final String title;

  /// Optional widget shown at the trailing edge (e.g. a button).
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: 8, bottom: 8),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(title, style: theme.textTheme.titleMedium),
          ?trailing,
        ],
      ),
    );
  }
}
