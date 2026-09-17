package io.github.originalrecipe1.unfurlit.data.extractor.tiktok

import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.domain.model.PlaybackSource
import io.github.originalrecipe1.unfurlit.domain.model.StreamFormat
import io.github.originalrecipe1.unfurlit.util.UrlValidator
import org.json.JSONObject
import java.net.URI

/** Reads only the requested post, never recommendations or page thumbnails. */
internal object TikTokPhotoParser {
    fun canonicalPage(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!UrlValidator.isAllowedHttps(url) || uri.host !in HOSTS) return null
        val match = PHOTO_PATH.matchEntire(uri.path.orEmpty()) ?: return null
        return "https://www.tiktok.com/@${match.groupValues[1]}/video/${match.groupValues[2]}"
    }

    fun parse(sourceUrl: String, pageUrl: String, html: String): ExtractionResult {
        if (html.length > MAX_PAGE_BYTES) fail(ExtractionError.ExtractionFailed)
        val json = STATE.find(html)?.groupValues?.get(1) ?: fail(ExtractionError.ExtractionFailed)
        val detail = runCatching {
            JSONObject(json).getJSONObject("__DEFAULT_SCOPE__").getJSONObject("webapp.video-detail")
        }.getOrElse { fail(ExtractionError.ExtractionFailed) }
        when (detail.optInt("statusCode", -1)) {
            0 -> Unit
            10216, 10222 -> fail(ExtractionError.AuthenticationRequired)
            10204 -> fail(ExtractionError.ExtractionFailed)
            else -> fail(ExtractionError.MediaUnavailable)
        }
        val item = detail.optJSONObject("itemInfo")?.optJSONObject("itemStruct")
            ?: fail(ExtractionError.ExtractionFailed)
        val expectedId = URI(pageUrl).path.substringAfterLast('/')
        if (item.optString("id") != expectedId) fail(ExtractionError.ExtractionFailed)
        val images = item.optJSONObject("imagePost")?.optJSONArray("images")
            ?: fail(ExtractionError.UnsupportedUrl)
        if (images.length() !in 1..50) fail(ExtractionError.ExtractionFailed)
        val headers = mapOf("Referer" to pageUrl)
        val media = (0 until images.length()).map { index ->
            val urls = images.optJSONObject(index)?.optJSONObject("imageURL")?.optJSONArray("urlList")
                ?: fail(ExtractionError.ExtractionFailed)
            val url = (0 until minOf(urls.length(), 20)).asSequence()
                .map { urls.optString(it) }.firstOrNull(UrlValidator::isAllowedHttps)
                ?: fail(ExtractionError.UnsupportedUrl)
            ExtractedMedia.Image(source(url, headers))
        }
        val music = item.optJSONObject("music")
        val musicUrl = music?.text("playUrl")
        val audio = musicUrl?.let {
            if (!UrlValidator.isAllowedHttps(it)) fail(ExtractionError.UnsupportedUrl)
            ExtractedMedia.Audio(
                source = source(it, headers),
                durationSeconds = music.optLong("duration", -1).takeIf { duration -> duration >= 0 },
                artworkUrl = null,
            )
        }
        val description = item.text("desc")?.take(16_384)
        return ExtractionResult(
            sourceUrl = sourceUrl,
            platform = "TikTok",
            title = item.optJSONObject("imagePost")?.text("title")?.take(512)
                ?: description?.take(512),
            author = item.optJSONObject("author")?.text("uniqueId")?.take(512),
            description = description,
            thumbnailUrl = media.first().source.url,
            media = media,
            backgroundAudio = audio,
        )
    }

    private fun source(url: String, headers: Map<String, String>) = PlaybackSource(
        url = url, headers = headers, format = StreamFormat.Progressive,
        mediaMimeType = null, formatId = null,
    )

    private fun JSONObject.text(key: String): String? = optString(key)
        .trim().takeIf { it.isNotEmpty() && it != "null" }

    private fun fail(error: ExtractionError): Nothing = throw ExtractionException(error)

    const val MAX_PAGE_BYTES = 4 * 1024 * 1024
    private val HOSTS = setOf("tiktok.com", "www.tiktok.com", "m.tiktok.com")
    private val PHOTO_PATH = Regex("/@([A-Za-z0-9_.-]+)/photo/([0-9]+)/?")
    private val STATE = Regex(
        """<script\b[^>]*\bid\s*=\s*["']__UNIVERSAL_DATA_FOR_REHYDRATION__["'][^>]*>(.*?)</script\s*>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
}
