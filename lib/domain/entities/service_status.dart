/// Represents the background foreground-service state.
///
/// Pure domain enum decoupled from any platform representation.
enum ServiceStatus {
  /// Service has never been started.
  stopped,

  /// Service is currently running in the foreground.
  running,

  /// Service is in the process of starting or stopping.
  transitioning,
}
