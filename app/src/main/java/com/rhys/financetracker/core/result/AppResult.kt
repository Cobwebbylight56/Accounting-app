package com.rhys.financetracker.core.result

/**
 * A small result type used by every operation that can fail for a reason the
 * user should see (import problems, backup failures, validation errors).
 *
 * Using this instead of throwing keeps error handling explicit: the UI layer
 * cannot forget to render a failure because it has to unwrap the value.
 */
sealed interface AppResult<out T> {

    data class Success<T>(val data: T) : AppResult<T>

    /**
     * @param message a message that is safe and useful to show to a non-technical user.
     * @param cause the underlying exception, kept for logging only.
     */
    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : AppResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.data

    fun errorMessageOrNull(): String? = (this as? Failure)?.message
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

inline fun <T> AppResult<T>.onFailure(action: (AppResult.Failure) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(this)
    return this
}

/**
 * Runs [block], converting any thrown exception into an [AppResult.Failure]
 * carrying [fallbackMessage].  Cancellation is re-thrown so that coroutine
 * cancellation still works correctly.
 */
inline fun <T> runCatchingApp(
    fallbackMessage: String,
    block: () -> T,
): AppResult<T> = try {
    AppResult.Success(block())
} catch (cancellation: kotlinx.coroutines.CancellationException) {
    throw cancellation
} catch (error: Throwable) {
    AppResult.Failure(error.message?.takeIf { it.isNotBlank() } ?: fallbackMessage, error)
}
