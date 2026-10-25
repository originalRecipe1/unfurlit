package io.github.originalrecipe1.unfurlit.data.extractor.pixiv

import io.github.originalrecipe1.unfurlit.data.network.PublicPageLoader
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.domain.model.PlaybackSource
import io.github.originalrecipe1.unfurlit.domain.model.StreamFormat
import io.github.originalrecipe1.unfurlit.util.UrlValidator
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.net.URI

/** Public artwork metadata from Pixiv's web API, without its authenticated mobile API. */
internal object PixivArtworkParser {
    fun artworkId(url: String): String? {
        if (!UrlValidator.isAllowedHttps(url)) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.rawUserInfo != null || uri.port !in listOf(-1, 443) ||
            uri.host?.lowercase()?.trimEnd('.') !in HOSTS) return null
        val path = uri.rawPath.orEmpty()
        val id = if (path == "/member_illust.php") {
            url.toHttpUrlOrNull()?.queryParameterValues("illust_id")?.singleOrNull()
        } else {
            ARTWORK_PATH.matchEntire(path)?.groupValues?.get(1)
        }
        return id?.takeIf { ID.matches(it) }
    }

    fun canonicalPage(url: String): String? = artworkId(url)?.let { "https://www.pixiv.net/artworks/$it" }

    data class Artwork(
        val id: String,
        val title: String?,
        val author: String?,
        val count: Int,
        val original: String,
        val regular: String? = null,
    )

    fun artwork(id: String, json: String): Artwork {
        val body = response(json).optJSONObject("body") ?: fail()
        if (!ID.matches(id) || body.optString("id") != id) fail()
        // Do not turn login-only artwork (or its placeholder) into a successful image.
        val restriction = body.optInt("restrict", -1)
        val ageRestriction = body.optInt("xRestrict", -1)
        if (restriction < 0 || ageRestriction < 0) fail()
        if (restriction != 0 || ageRestriction != 0) {
            throw ExtractionException(ExtractionError.AuthenticationRequired)
        }
        // Ugoira is a timed frame archive, not the still preview in urls.original.
        if (body.optInt("illustType", -1) !in 0..1) {
            throw ExtractionException(ExtractionError.UnsupportedUrl)
        }
        val count = body.optInt("pageCount", -1)
        if (count !in 1..50) fail()
        val urls = body.optJSONObject("urls")
        return Artwork(id, body.text("title")?.take(512), body.text("userName")?.take(512), count,
            urls?.text("original") ?: fail(), urls?.text("regular"))
    }

    fun parse(sourceUrl: String, artwork: Artwork, pagesJson: String? = null): ExtractionResult {
        val pages = if (artwork.count == 1) {
            listOf(artwork.original to artwork.regular)
        } else {
            val pages = response(pagesJson ?: fail()).optJSONArray("body") ?: fail()
            if (pages.length() != artwork.count) fail()
            // Read every original URL in API order: extensions and hashes can differ per page.
            (0 until pages.length()).map {
                val urls = pages.optJSONObject(it)?.optJSONObject("urls")
                (urls?.text("original") ?: fail()) to urls?.text("regular")
            }
        }
        val media = pages.mapIndexed { index, (original, regular) ->
            if (!isImage(original, artwork.id, index, "img-original", "")) fail()
            // Pixiv's own resized copy covers originals its CDN cannot serve; an unusable one is dropped, not fatal.
            val fallback = regular?.takeIf { isImage(it, artwork.id, index, "img-master", "_master1200") }
            ExtractedMedia.Image(PlaybackSource(
                url = original,
                headers = mapOf("Referer" to "https://www.pixiv.net/"),
                format = StreamFormat.Progressive,
                mediaMimeType = null,
                formatId = null,
            ), fallback)
        }
        return ExtractionResult(
            sourceUrl = sourceUrl,
            platform = "Pixiv",
            title = artwork.title,
            author = artwork.author,
            description = null,
            thumbnailUrl = media.first().source.url,
            media = media,
        )
    }

    private fun isImage(url: String, id: String, page: Int, folder: String, suffix: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return UrlValidator.isAllowedHttps(url) && uri.rawUserInfo == null &&
            uri.port in listOf(-1, 443) && uri.host?.lowercase() == "i.pximg.net" &&
            uri.path.orEmpty().startsWith("/$folder/") &&
            Regex("$id(?:-[a-zA-Z0-9]+)?_p$page$suffix\\.(?:jpg|jpeg|png|gif|webp|avif)")
                .matches(uri.path.substringAfterLast('/'))
    }

    private fun response(json: String): JSONObject {
        if (json.length > PublicPageLoader.MAX_PAGE_BYTES) fail()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: fail()
        if (root.opt("error") != false) fail()
        return root
    }

    private fun JSONObject.text(key: String) = optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
    private fun fail(): Nothing = throw ExtractionException(ExtractionError.ExtractionFailed)
    private val HOSTS = setOf("pixiv.net", "www.pixiv.net", "touch.pixiv.net")
    private val ID = Regex("[1-9][0-9]{0,19}")
    private val ARTWORK_PATH = Regex("/(?:artworks/|en/artworks/|i/)([1-9][0-9]{0,19})/?")
}
