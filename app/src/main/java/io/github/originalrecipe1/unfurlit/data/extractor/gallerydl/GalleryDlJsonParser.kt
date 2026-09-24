package io.github.originalrecipe1.unfurlit.data.extractor.gallerydl

import io.github.originalrecipe1.unfurlit.data.extractor.MediaRequestHeaders
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.domain.model.PlaybackSource
import io.github.originalrecipe1.unfurlit.domain.model.StreamFormat
import io.github.originalrecipe1.unfurlit.util.UrlValidator
import org.json.JSONObject

/** Parses the single JSON object printed by the bundled gallery-dl entry point. */
internal object GalleryDlJsonParser {
    fun parse(sourceUrl: String, output: String): ExtractionResult {
        if (output.length > MAX_OUTPUT_LENGTH) fail(ExtractionError.ExtractionFailed)
        // Log lines may precede the result, which is always printed last.
        val line = output.lineSequence().map(String::trim).lastOrNull { it.startsWith("{") }
            ?: fail(ExtractionError.ExtractionFailed)
        val root = runCatching { JSONObject(line) }.getOrElse { fail(ExtractionError.ExtractionFailed) }
        root.optJSONObject("error")?.let { error ->
            // Keep gallery-dl's own reason for the (redacted) failure log.
            val detail = "gallery-dl ${error.text("type")} ${error.optInt("status", 0)}: ${error.text("message")}"
            fail(error.toDomainError(), IllegalStateException(detail.take(MAX_SHORT_TEXT)))
        }

        val items = root.optJSONArray("items") ?: fail(ExtractionError.ExtractionFailed)
        val media = (0 until minOf(items.length(), MAX_MEDIA_ENTRIES))
            .mapNotNull { index -> items.optJSONObject(index)?.toMedia() }
        if (media.isEmpty()) fail(ExtractionError.UnsupportedUrl)

        return ExtractionResult(
            sourceUrl = sourceUrl,
            platform = root.text("category")?.let(::platformName),
            title = root.text("title")?.take(MAX_SHORT_TEXT),
            author = root.text("author")?.take(MAX_SHORT_TEXT),
            description = root.text("description")?.take(MAX_DESCRIPTION),
            thumbnailUrl = media.firstNotNullOfOrNull { (it as? ExtractedMedia.Image)?.source?.url },
            media = media,
        )
    }

    private fun JSONObject.toMedia(): ExtractedMedia? {
        val url = text("url")?.takeIf(UrlValidator::isAllowedHttps) ?: return null
        val extension = text("extension")?.lowercase().orEmpty()
        val headers = optJSONObject("headers")?.let { headers ->
            MediaRequestHeaders.sanitize(
                headers.keys().asSequence().map { name -> name to headers.text(name) },
            )
        }.orEmpty()
        fun source(mimeType: String?) = PlaybackSource(
            url = url,
            headers = headers,
            format = StreamFormat.Progressive,
            mediaMimeType = mimeType,
            formatId = null,
        )
        return when (text("kind")) {
            "image" -> ExtractedMedia.Image(source(null))
            "video" -> ExtractedMedia.Video(source(VIDEO_MIME_TYPES[extension]), null, null)
            "audio" -> ExtractedMedia.Audio(source(AUDIO_MIME_TYPES[extension]), null, null)
            else -> null
        }
    }

    private fun JSONObject.toDomainError(): ExtractionError {
        val status = optInt("status", 0)
        return when (text("type")) {
            "NoExtractorError" -> ExtractionError.UnsupportedUrl
            "NotFoundError" -> ExtractionError.MediaUnavailable
            "AuthRequired", "AuthorizationError", "AuthenticationError" ->
                ExtractionError.AuthenticationRequired
            // e.g. Reddit's "You've been blocked by network security" page.
            "AbortExtraction" -> if (text("message")?.contains("blocked", ignoreCase = true) == true) {
                ExtractionError.AuthenticationRequired
            } else {
                ExtractionError.ExtractionFailed
            }
            "HttpError" -> when {
                status == 404 || status == 410 -> ExtractionError.MediaUnavailable
                status == 401 || status == 403 -> ExtractionError.AuthenticationRequired
                // 0 means no HTTP response: connection, DNS, or TLS failure.
                status == 0 || status == 429 || status >= 500 -> ExtractionError.NetworkFailure
                else -> ExtractionError.ExtractionFailed
            }
            else -> ExtractionError.ExtractionFailed
        }
    }

    /** Display names for gallery-dl extractor categories; others are capitalized. */
    internal fun platformName(category: String): String = PLATFORM_NAMES[category]
        ?: category.replaceFirstChar { it.uppercaseChar() }

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf(String::isNotEmpty)

    private fun fail(error: ExtractionError, cause: Throwable? = null): Nothing =
        throw ExtractionException(error, cause)

    private const val MAX_OUTPUT_LENGTH = 2 * 1024 * 1024
    private const val MAX_MEDIA_ENTRIES = 50
    private const val MAX_SHORT_TEXT = 512
    private const val MAX_DESCRIPTION = 16_384
    private val VIDEO_MIME_TYPES = mapOf("mp4" to "video/mp4", "m4v" to "video/mp4", "webm" to "video/webm", "mov" to "video/quicktime")
    private val AUDIO_MIME_TYPES = mapOf(
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg",
        "oga" to "audio/ogg", "opus" to "audio/ogg", "wav" to "audio/wav", "flac" to "audio/flac",
    )
    private val PLATFORM_NAMES = mapOf(
        "twitter" to "X/Twitter",
        "reddit" to "Reddit",
        "imgur" to "Imgur",
        "instagram" to "Instagram",
        "tiktok" to "TikTok",
        "bluesky" to "Bluesky",
        "mastodon" to "Mastodon",
        "tumblr" to "Tumblr",
        "pixiv" to "Pixiv",
        "deviantart" to "DeviantArt",
        "flickr" to "Flickr",
        "pinterest" to "Pinterest",
        "artstation" to "ArtStation",
        "directlink" to "Image",
    )
}
