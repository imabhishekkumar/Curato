package com.curato.domain

/**
 * Every failure the client can show, as a code (SPEC.md §5.1).
 *
 * The client branches on this and **never on message text**, which is what lets the
 * backend reword a message without a client release.
 */
enum class ErrorCode {
    VALIDATION_FAILED,
    NOT_FOUND,
    MALFORMED_REQUEST,
    PAYLOAD_TOO_LARGE,
    METHOD_NOT_ALLOWED,
    EMAIL_SEND_FAILED,
    UPSTREAM_UNAVAILABLE,
    RATE_LIMITED,
    INTERNAL_ERROR,

    // Client-side only: no envelope exists to read, so these are synthesised here and
    // carry their own copy.
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    UNEXPECTED_RESPONSE,

    /**
     * A code this build does not know.
     *
     * The backend may add codes before the client is rebuilt. Degrading to a generic
     * error is correct; failing the parse would turn a forward-compatible API change
     * into a crash.
     */
    UNKNOWN;

    companion object {
        fun fromWire(raw: String?): ErrorCode =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

/**
 * A failure, ready to render.
 *
 * `developerMessage` is deliberately absent: it ships in the response body but is never
 * shown, so it is dropped at the boundary rather than carried into the UI layer where
 * someone could render it by accident.
 */
data class DomainError(
    val code: ErrorCode,
    /** The only general error string shown to a customer. */
    val userMessage: String,
    /** Dotted request paths to inline messages. Empty unless the failure is field-level. */
    val fieldErrors: Map<String, String> = emptyMap(),
    /** Surfaced only on [ErrorCode.INTERNAL_ERROR], in a copyable form. */
    val traceId: String? = null,
) {
    /**
     * Whether offering Retry can help.
     *
     * Not every failure is retryable, and offering Retry where retrying cannot help is
     * worse than offering nothing — a product that no longer exists will not start
     * existing because the customer tapped again.
     */
    val isRetryable: Boolean
        get() = when (code) {
            ErrorCode.NOT_FOUND,
            ErrorCode.VALIDATION_FAILED,
            ErrorCode.MALFORMED_REQUEST,
            ErrorCode.PAYLOAD_TOO_LARGE,
            ErrorCode.METHOD_NOT_ALLOWED,
            -> false

            else -> true
        }
}

/** The single result type every repository call returns. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Fail(val error: DomainError) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(transform(value))
    is ApiResult.Fail -> this
}
