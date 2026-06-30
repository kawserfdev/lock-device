/// A simple discriminated union representing either a success value [S]
/// or a failure [F].
///
/// Used across the domain/data layers so that repositories can return
/// failures explicitly instead of throwing exceptions. The presentation
/// layer pattern-matches via `when` to render success or error states.
sealed class Result<S, F> {
  const Result();

  /// Folds the result into a single value, calling [onSuccess] or [onFailure].
  R when<R>({
    required R Function(S value) onSuccess,
    required R Function(F failure) onFailure,
  }) {
    final Result<S, F> self = this;
    if (self is Success<S, F>) {
      return onSuccess(self.value);
    }
    return onFailure((self as Failure<S, F>).error);
  }
}

/// Successful result carrying a value of type [S].
final class Success<S, F> extends Result<S, F> {
  /// Constructs a success result.
  const Success(this.value);

  /// The success value.
  final S value;
}

/// Failed result carrying an error of type [F].
final class Failure<S, F> extends Result<S, F> {
  /// Constructs a failure result.
  const Failure(this.error);

  /// The failure value.
  final F error;
}
