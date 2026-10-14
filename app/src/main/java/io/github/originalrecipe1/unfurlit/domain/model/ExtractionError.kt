package io.github.originalrecipe1.unfurlit.domain.model

sealed interface ExtractionError {
    data object UnsupportedUrl : ExtractionError
    data object MediaUnavailable : ExtractionError
    data object AuthenticationRequired : ExtractionError
    /** The platform refused this network (bot check or IP block), not the account or post. */
    data class NetworkBlocked(val platform: String) : ExtractionError
    data object NetworkFailure : ExtractionError
    data object Timeout : ExtractionError
    data object ExtractionFailed : ExtractionError
}

class ExtractionException(
    val error: ExtractionError,
    cause: Throwable? = null,
) : Exception(error.toString(), cause)

val ExtractionError.canRetry: Boolean
    get() = this == ExtractionError.NetworkFailure ||
        this is ExtractionError.NetworkBlocked ||
        this == ExtractionError.Timeout ||
        this == ExtractionError.ExtractionFailed
