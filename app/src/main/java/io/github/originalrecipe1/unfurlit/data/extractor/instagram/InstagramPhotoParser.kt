package io.github.originalrecipe1.unfurlit.data.extractor.instagram

import io.github.originalrecipe1.unfurlit.data.network.PublicPageLoader
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.domain.model.PlaybackSource
import io.github.originalrecipe1.unfurlit.domain.model.StreamFormat
import io.github.originalrecipe1.unfurlit.util.UrlValidator
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal object InstagramPhotoParser {
    fun canonicalPage(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!UrlValidator.isAllowedHttps(url) || uri.host !in HOSTS) return null
        val match = POST_PATH.matchEntire(uri.path.orEmpty()) ?: return null
        return "https://www.instagram.com/p/${match.groupValues[1]}"
    }

    /** Null leaves video posts and unrecognized page layouts to the existing extractor. */
    fun parse(sourceUrl: String, pageUrl: String, html: String): ExtractionResult? {
        if (html.length > PublicPageLoader.MAX_PAGE_BYTES) fail()
        val code = URI(pageUrl).path.trimEnd('/').substringAfterLast('/')
        var visited = 0
        fun findProduct(value: Any?, depth: Int): JSONObject? {
            if (++visited > 50_000 || depth > 80) fail()
            when (value) {
                is JSONObject -> {
                    val product = value.optJSONObject("xig_polaris_media")
                        ?.optJSONObject("if_not_gated_logged_out")
                    if (product?.optString("code") == code) return product
                    for (key in value.keys()) findProduct(value.opt(key), depth + 1)?.let { return it }
                }
                is JSONArray -> for (index in 0 until value.length()) {
                    findProduct(value.opt(index), depth + 1)?.let { return it }
                }
            }
            return null
        }
        val product = SCRIPTS.findAll(html).mapNotNull { match ->
            runCatching { JSONObject(match.groupValues[1]) }.getOrNull()
                ?.let { findProduct(it, 0) }
        }.firstOrNull() ?: return null
        val carousel = product.optJSONArray("carousel_media")
        if (carousel != null && carousel.length() !in 1..50) fail()
        val items = if (carousel == null) listOf(product) else (0 until carousel.length()).map {
            carousel.optJSONObject(it) ?: fail()
        }
        // Pure video posts retain yt-dlp's format selection and DASH support.
        if (items.none { it.optInt("media_type") == 1 }) return null
        val headers = mapOf("Referer" to "https://www.instagram.com/")
        fun source(url: String) = PlaybackSource(
            url, headers, StreamFormat.Progressive, mediaMimeType = null, formatId = null,
        )
        fun JSONObject.bestUrl(key: String): String {
            val candidates = optJSONArray(key) ?: fail()
            val available = (0 until minOf(candidates.length(), 100)).mapNotNull { candidates.optJSONObject(it) }
                .filter { UrlValidator.isAllowedHttps(it.optString("url")) }
            val preferred = if (key == "video_versions") {
                available.filter { it.optInt("height", 0) <= 1080 }.ifEmpty { available }
            } else available
            return preferred.maxByOrNull { it.optLong("width", 0).coerceIn(0, 20_000) * it.optLong("height", 0).coerceIn(0, 20_000) }
                ?.getString("url") ?: throw ExtractionException(ExtractionError.UnsupportedUrl)
        }
        val media = items.map { item ->
            when (item.optInt("media_type")) {
                1 -> ExtractedMedia.Image(source(
                    (item.optJSONObject("image_versions2") ?: fail()).bestUrl("candidates")))
                2 -> ExtractedMedia.Video(source(item.bestUrl("video_versions")), null,
                    item.optDouble("video_duration", -1.0).takeIf { it >= 0 }?.toLong())
                else -> fail()
            }
        }
        val caption = product.optJSONObject("caption")?.text("text")?.take(16_384)
        return ExtractionResult(
            sourceUrl = sourceUrl,
            platform = "Instagram",
            title = caption?.take(512),
            author = product.optJSONObject("user")?.text("username")?.take(512),
            description = caption,
            thumbnailUrl = (media.firstOrNull() as? ExtractedMedia.Image)?.source?.url,
            media = media,
        )
    }

    private fun JSONObject.text(key: String) = optString(key).trim()
        .takeIf { it.isNotEmpty() && it != "null" }
    private fun fail(): Nothing = throw ExtractionException(ExtractionError.ExtractionFailed)
    private val HOSTS = setOf("instagram.com", "www.instagram.com", "m.instagram.com")
    private val POST_PATH = Regex("/p/([A-Za-z0-9_-]{1,64})/?")
    private val SCRIPTS = Regex(
        """<script\b[^>]*\bdata-sjs(?:\s*=\s*["'][^"']*["'])?[^>]*>(.*?)</script\s*>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
}
