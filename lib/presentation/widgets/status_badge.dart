import 'package:flutter/material.dart';

/// A reusable status badge widget.
///
/// Displays a label with a coloured dot indicating a state (e.g. active,
/// inactive, warning). Used across screens to render status consistently.
class StatusBadge extends StatelessWidget {
  /// Constructs a status badge.
  const StatusBadge({
    required this.label,
    required this.color,
    super.key,
  });

  /// Text shown next to the dot.
  final String label;

  /// Colour of the indicator dot.
  final Color color;

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 8,
            height: 8,
            decoration: BoxDecoration(color: color, shape: BoxShape.circle),
          ),
          const SizedBox(width: 8),
          Text(label, style: theme.textTheme.labelMedium),
        ],
      ),
    );
  }
}
