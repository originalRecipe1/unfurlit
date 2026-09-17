package io.github.originalrecipe1.unfurlit.domain.model

sealed interface ExtractionError {
    data object UnsupportedUrl : ExtractionError
    data object MediaUnavailable : ExtractionError
    data object AuthenticationRequired : ExtractionError
    data object NetworkFailure : ExtractionError
    data object ExtractionFailed : ExtractionError
}

class ExtractionException(
    val error: ExtractionError,
    cause: Throwable? = null,
) : Exception(error.userMessage, cause)

val ExtractionError.userMessage: String
    get() = when (this) {
        ExtractionError.UnsupportedUrl -> "No viewable media found"
        ExtractionError.MediaUnavailable -> "Media unavailable"
        ExtractionError.AuthenticationRequired -> "Sign-in may be needed"
        ExtractionError.NetworkFailure -> "Couldn’t reach this site"
        ExtractionError.ExtractionFailed -> "Couldn’t open this media"
    }

val ExtractionError.canRetry: Boolean
    get() = this == ExtractionError.NetworkFailure || this == ExtractionError.ExtractionFailed

val ExtractionError.recoveryMessage: String
    get() = when (this) {
        ExtractionError.UnsupportedUrl ->
            "This link may be a page without media, or a site Unfurlit doesn’t support. Try a direct link to a post, video, or audio track."
        ExtractionError.MediaUnavailable ->
            "This post may have been removed or may no longer be available. Open the original to check, or try another link."
        ExtractionError.AuthenticationRequired ->
            "This site may require sign-in or restrict access to this post. Open the original in your browser to check."
        ExtractionError.NetworkFailure ->
            "Check your connection and try again. The site may also be temporarily unavailable."
        ExtractionError.ExtractionFailed ->
            "Unfurlit couldn’t read the media in this link. Try again, or open the original to view it on the site."
    }
