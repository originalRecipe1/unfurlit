package io.github.originalrecipe1.unfurlit.data.extractor.tumblr

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

/** Photo posts exposed in Tumblr's anonymous permalink page, without API credentials. */
internal object TumblrPhotoParser {
    fun canonicalPage(url: String): String? {
        if (!UrlValidator.isAllowedHttps(url)) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) return null
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        val path = uri.rawPath.orEmpty()
        val (blog, id) = if (host == "tumblr.com" || host == "www.tumblr.com") {
            val match = WEB_POST.matchEntire(path) ?: return null
            match.groupValues[1] to match.groupValues[2]
        } else {
            val blog = BLOG_HOST.matchEntire(host)?.groupValues?.get(1) ?: return null
            val id = BLOG_POST.matchEntire(path)?.groupValues?.get(1) ?: return null
            blog to id
        }
        return "https://www.tumblr.com/${blog.lowercase()}/$id"
    }

    /** Null leaves video, audio, mixed posts and unknown page layouts to the existing engines. */
    fun parse(sourceUrl: String, pageUrl: String, html: String): ExtractionResult? {
        val canonical = canonicalPage(pageUrl) ?: return null
        if (html.length > PublicPageLoader.MAX_PAGE_BYTES) fail()
        val (_, blog, id) = URI(canonical).path.split('/')
        val stateText = INITIAL_STATE.find(html)?.groupValues?.get(1) ?: return null
        val state = runCatching { JSONObject(stateText) }.getOrNull() ?: return null
        // Only permalink timeline posts qualify, never avatars, recommendations,
        // inline link previews or unrelated objects elsewhere in the page state.
        val posts = state.optJSONObject("PeeprRoute")?.optJSONObject("initialTimeline")
            ?.optJSONArray("objects") ?: return null
        val post = posts.objects(50).firstOrNull {
            it.optString("objectType") == "post" &&
                it.optString("id") == id && it.optString("blogName").equals(blog, ignoreCase = true)
        } ?: return null
        // NPF trails run oldest to newest, followed by the reblog's own content.
        val parts = post.optJSONArray("trail")?.objects(50).orEmpty() + post
        val blocks = parts.flatMap { orderedContent(it) }
        if (blocks.any { it.optString("type") !in setOf("image", "text") }) return null
        val images = blocks.filter { it.optString("type") == "image" }
        if (images.isEmpty()) return null
        if (images.size > 50) fail()
        val media = images.map { block ->
            val candidates = block.optJSONArray("media")?.objects(100) ?: fail()
            val safe = candidates.filter {
                it.optString("type").startsWith("image/") &&
                    UrlValidator.isAllowedHttps(it.optString("url")) &&
                    URI(it.getString("url")).rawUserInfo == null
            }
            val uncropped = safe.filterNot { it.optBoolean("cropped", false) }.ifEmpty { safe }
            val best = uncropped.maxByOrNull {
                it.optLong("width", 0).coerceIn(0, 20_000) * it.optLong("height", 0).coerceIn(0, 20_000)
            } ?: throw ExtractionException(ExtractionError.UnsupportedUrl)
            // Use the media itself, including GIFs; never its still `poster`.
            ExtractedMedia.Image(PlaybackSource(
                url = best.getString("url"),
                headers = mapOf("Referer" to "https://www.tumblr.com/"),
                format = StreamFormat.Progressive,
                mediaMimeType = best.getString("type").take(128),
                formatId = null,
            ))
        }
        val description = blocks.filter { it.optString("type") == "text" }
            .mapNotNull { it.text("text") }.joinToString("\n\n").take(16_384).ifEmpty { null }
        return ExtractionResult(
            sourceUrl = sourceUrl,
            platform = "Tumblr",
            title = (post.text("summary") ?: description)?.take(512),
            author = post.text("blogName")?.take(512),
            description = description,
            thumbnailUrl = media.first().source.url,
            media = media,
        )
    }

    private fun orderedContent(part: JSONObject): List<JSONObject> {
        val content = part.optJSONArray("content")?.objects(250) ?: fail()
        val layouts = part.optJSONArray("layout")?.objects(50).orEmpty()
        val rows = layouts.singleOrNull { it.optString("type") == "rows" } ?: return content
        val order = linkedSetOf<Int>()
        for (row in rows.optJSONArray("display")?.objects(250) ?: fail()) {
            val indices = row.optJSONArray("blocks") ?: fail()
            if (indices.length() > content.size) fail()
            for (index in 0 until indices.length()) {
                val block = indices.opt(index) as? Int ?: fail()
                if (block !in content.indices) fail()
                order += block
            }
        }
        // Keep blocks omitted by older layouts rather than silently losing images.
        order.addAll(content.indices)
        return order.map { content[it] }
    }

    private fun JSONArray.objects(limit: Int): List<JSONObject> {
        if (length() > limit) fail()
        return (0 until length()).map { optJSONObject(it) ?: fail() }
    }

    private fun JSONObject.text(key: String) = optString(key).trim()
        .takeIf { it.isNotEmpty() && it != "null" }
    private fun fail(): Nothing = throw ExtractionException(ExtractionError.ExtractionFailed)
    private const val BLOG = "[A-Za-z0-9][A-Za-z0-9-]{0,62}"
    private val BLOG_HOST = Regex("($BLOG)\\.tumblr\\.com")
    private val BLOG_POST = Regex("/(?:post|image)/([0-9]{1,20})(?:/[^/]+)?/?")
    private val WEB_POST = Regex("/(?:(?:blog/view|blog)/)?($BLOG)/([0-9]{1,20})(?:/[^/]+)?/?")
    private val INITIAL_STATE = Regex(
        """<script\b[^>]*\bid\s*=\s*["']___INITIAL_STATE___["'][^>]*>(.*?)</script\s*>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
}
