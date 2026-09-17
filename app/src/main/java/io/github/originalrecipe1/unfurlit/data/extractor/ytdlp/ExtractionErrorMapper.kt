package io.github.originalrecipe1.unfurlit.data.extractor.ytdlp

import io.github.originalrecipe1.unfurlit.data.network.UnsafeNetworkTargetException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException

internal fun Throwable.toDomainError(): ExtractionError {
    val causes = generateSequence(this) { it.cause }.toList()
    val detail = causes.mapNotNull(Throwable::message).joinToString(" ").lowercase()
    fun mentions(vararg phrases: String) = phrases.any(detail::contains)

    return when {
        causes.any { it is UnsafeNetworkTargetException } -> ExtractionError.UnsupportedUrl
        causes.any { it is IOException || it is TimeoutCancellationException } ||
            mentions("network", "timed out", "connection", "dns") -> ExtractionError.NetworkFailure
        mentions("sign in", "sign-in", "login", "log in", "logged-in", "cookies required", "private video", "authentication") ->
            ExtractionError.AuthenticationRequired
        mentions(
            "unsupported url", "no suitable extractor", "no video could be found",
            "no video formats found", "does not contain any video",
        ) -> ExtractionError.UnsupportedUrl
        mentions("unavailable", "removed", "deleted", "not available", "http error 404", "http error 410") ->
            ExtractionError.MediaUnavailable
        else -> ExtractionError.ExtractionFailed
    }
}
