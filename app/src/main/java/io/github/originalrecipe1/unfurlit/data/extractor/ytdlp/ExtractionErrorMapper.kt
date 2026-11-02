package io.github.originalrecipe1.unfurlit.data.extractor.ytdlp

import io.github.originalrecipe1.unfurlit.data.network.UnsafeNetworkTargetException
import io.github.originalrecipe1.unfurlit.domain.model.BlockingPlatform
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException

internal fun Throwable.toDomainError(): ExtractionError {
    val causes = generateSequence(this) { it.cause }.toList()
    val detail = causes.mapNotNull(Throwable::message).joinToString(" ").lowercase()
    fun mentions(vararg phrases: String) = phrases.any(detail::contains)

    if (causes.any { it is UnsafeNetworkTargetException }) return ExtractionError.UnsupportedUrl
    // Before the generic checks: Reddit's block page mentions "network", YouTube's "sign in".
    networkBlock(detail)?.let { return it }
    return when {
        causes.any { it is IOException || it is TimeoutCancellationException } ||
            mentions("network", "timed out", "connection", "dns") ||
            // Python's resolver errors arrive as plain text from the engine, not as an IOException.
            mentions(
                "no address associated with hostname", "temporary failure in name resolution",
                "name or service not known",
            ) -> ExtractionError.NetworkFailure
        mentions("sign in", "sign-in", "login", "log in", "logged-in", "cookies required", "private video", "authentication") ->
            ExtractionError.AuthenticationRequired
        mentions(
            "unsupported url", "no suitable extractor", "no video could be found",
            "no video formats found", "does not contain any video", "no media found",
        ) -> ExtractionError.UnsupportedUrl
        mentions("unavailable", "removed", "deleted", "not available", "http error 404", "http error 410") ->
            ExtractionError.MediaUnavailable
        else -> ExtractionError.ExtractionFailed
    }
}

/**
 * Recognizes a platform refusing this network rather than the account or post. Only
 * literal upstream block responses qualify; "Please sign in", private and age-restricted
 * posts remain [ExtractionError.AuthenticationRequired].
 */
internal fun networkBlock(message: String): ExtractionError.NetworkBlocked? = BLOCKS
    .firstOrNull { (pattern, _) -> pattern.containsMatchIn(message) }
    ?.let { (_, platform) -> ExtractionError.NetworkBlocked(platform) }

private val BLOCKS = listOf(
    Regex("""\bsign in to confirm you['’]re not a bot\b""", RegexOption.IGNORE_CASE) to BlockingPlatform.YouTube,
    // gallery-dl quotes the text of Reddit's block page.
    Regex("""\bblocked by network security\b""", RegexOption.IGNORE_CASE) to BlockingPlatform.Reddit,
    // gallery-dl's HttpError for the same block, which Reddit sends with reason "Blocked".
    Regex("""'403 blocked' for 'https://(?:[\w-]+\.)*(?:reddit\.com|redd\.it)[/'?]""", RegexOption.IGNORE_CASE) to BlockingPlatform.Reddit,
    // Pinned yt-dlp (reddit.py) raises this only when anonymous and the .json response
    // isn't JSON, i.e. Reddit served its block page instead.
    Regex("""\[reddit] [0-9a-z]+: account authentication is required\b""", RegexOption.IGNORE_CASE) to BlockingPlatform.Reddit,
)
