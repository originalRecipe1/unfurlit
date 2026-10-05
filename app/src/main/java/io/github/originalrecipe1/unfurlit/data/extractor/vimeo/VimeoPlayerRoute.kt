package io.github.originalrecipe1.unfurlit.data.extractor.vimeo

import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.util.UrlValidator
import java.net.URI

/** Prefer embedded player configuration over Vimeo's authenticated page API. */
internal object VimeoPlayerRoute {
    fun playerUrl(url: String): String? {
        if (!UrlValidator.isAllowed(url)) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.rawUserInfo != null) return null
        if (uri.host?.lowercase()?.trimEnd('.') !in setOf("vimeo.com", "www.vimeo.com")) return null
        val defaultPort = if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
        if (uri.port != -1 && uri.port != defaultPort) return null
        val match = PAGE_PATH.matchEntire(uri.rawPath.orEmpty()) ?: return null
        val id = match.groupValues[1]
        val hash = match.groupValues[2]
        return "https://player.vimeo.com/video/$id" + if (hash.isEmpty()) "" else "?h=$hash"
    }

    suspend fun extract(
        originalUrl: String,
        extract: suspend (sourceUrl: String, requestUrl: String) -> ExtractionResult,
    ): ExtractionResult {
        playerUrl(originalUrl)?.let { playerUrl ->
            try {
                return extract(originalUrl, playerUrl)
            } catch (error: ExtractionException) {
                if (error.error == ExtractionError.Timeout || error.error == ExtractionError.NetworkFailure) {
                    throw error
                }
                // Embedding can be disabled even when the original page is usable.
                // Each attempt still runs the normal preflight and extraction pipeline.
            }
        }
        return extract(originalUrl, originalUrl)
    }

    private val PAGE_PATH = Regex("/([0-9]+)(?:/([A-Za-z0-9]+))?/?")
}
