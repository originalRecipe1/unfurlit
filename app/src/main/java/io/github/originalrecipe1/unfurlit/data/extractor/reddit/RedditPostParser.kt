package io.github.originalrecipe1.unfurlit.data.extractor.reddit

import io.github.originalrecipe1.unfurlit.data.network.PublicPageLoader
import io.github.originalrecipe1.unfurlit.data.network.UnsafeNetworkTargetException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.util.UrlValidator
import java.net.URI

internal data class RedditPost(val title: String?, val author: String?, val content: RedditContent)
internal sealed interface RedditContent {
    data class Video(val manifest: String) : RedditContent
    data class Images(val urls: List<String>) : RedditContent
    data class External(val url: String) : RedditContent
}

/** Pure HTML discovery for the proven SSR post/carousel shapes. Unknown shapes return null. */
internal object RedditPostParser {
    const val MAX_MEDIA = 50

    fun parse(pageUrl: String, html: String): RedditPost? {
        val id = postId(pageUrl) ?: return null
        if (html.length > PublicPageLoader.MAX_PAGE_BYTES) return null
        val tags = tags(html)
        val start = tags.indexOfFirst { !it.closing && it.name == "shreddit-post" && it.attributes["id"] == "t3_$id" }
        if (start < 0) return null
        val own = mutableListOf<Tag>()
        var depth = 0
        var closed = false
        for (tag in tags.drop(start)) {
            if (tag.name == "shreddit-post") {
                if (tag.closing) {
                    depth--
                    if (depth == 0) { closed = true; break }
                } else depth++
            }
            // Nested crossposts/recommendations cannot provide this post's media.
            if (depth == 1 && (tag.name != "shreddit-post" || own.isEmpty())) own.add(tag)
        }
        if (!closed) return null
        val attributes = tags[start].attributes
        val content = content(attributes, own, id) ?: return null
        return RedditPost(attributes["post-title"]?.trim()?.takeIf(String::isNotEmpty)?.take(512),
            attributes["author"]?.trim()?.takeIf(String::isNotEmpty)?.take(512), content)
    }

    fun isChallenge(pageUrl: String, html: String): Boolean {
        val id = postId(pageUrl) ?: return false
        if (tags(html).any { it.name == "shreddit-post" && !it.closing }) return false
        if (listOf("blocked by network security", "your request has been blocked", "whoa there, pardner")
                .any { html.contains(it, ignoreCase = true) }) return true
        if (html.contains("t3_$id")) return false // Generic post shell, including 15ko0qu: fall through.
        val title = Regex("""<title\b[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let(::unescape)?.trim()
        return html.length <= 64 * 1024 && title.equals("Reddit", ignoreCase = true)
    }

    fun postId(url: String): String? {
        val uri = https(url) ?: return null
        if (!redditHost(uri.host)) return null
        return Regex("""/(?:comments|gallery)/([a-z0-9]+)(?:/|$)""").find(uri.rawPath.orEmpty())?.groupValues?.get(1)
    }

    fun isManifestUrl(url: String): Boolean = https(url)?.let {
        it.host.equals("v.redd.it", ignoreCase = true) &&
            it.path.matches(Regex("""/[A-Za-z0-9]+/[^/]+\.(?:m3u8|mpd)"""))
    } == true

    private fun content(attributes: Map<String, String>, tags: List<Tag>, id: String): RedditContent? {
        if (tags.any { it.name == "gallery-carousel" && !it.closing }) {
            return gallery(tags, id)?.let { RedditContent.Images(it) }
        }
        val players = tags.filter { it.name == "shreddit-player" && !it.closing }
        val manifests = players.mapNotNull { it.attributes["src"] }
        manifests.firstOrNull(::isManifestUrl)?.let { return RedditContent.Video(it) }
        val href = attributes["content-href"] ?: return null
        val uri = https(href) ?: return null
        image(href)?.let { return RedditContent.Images(listOf(it)) }
        if (uri.host.equals("v.redd.it", ignoreCase = true)) {
            // Never replace an explicit but invalid native source with a guessed manifest.
            // Foreign preview players on external posts do not override content-href.
            if (manifests.isNotEmpty()) return null
            if (!uri.path.matches(Regex("/[A-Za-z0-9]+/?"))) return null
            return RedditContent.Video("https://v.redd.it/${uri.path.trim('/')}/DASHPlaylist.mpd")
        }
        // Unsupported image hosts never masquerade as external videos. Reddit targets
        // (including crossposts and unresolved galleries) stay with the existing engines.
        if (attributes["post-type"] == "image" || redditHost(uri.host) || uri.host == "redd.it" || uri.host.endsWith(".redd.it")) return null
        return RedditContent.External(href)
    }

    private fun gallery(tags: List<Tag>, id: String): List<String>? {
        val carousel = tags.indexOfFirst { it.name == "gallery-carousel" && !it.closing }
        val carouselId = tags[carousel].attributes["post-id"]?.removePrefix("t3_")
        if (carouselId != null && carouselId != id) return null
        val items = sortedMapOf<Int, String>()
        var slot: Int? = null
        var liDepth = 0
        var candidates = mutableListOf<String>()
        var closed = false
        for (tag in tags.drop(carousel + 1)) {
            if (tag.name == "gallery-carousel") {
                if (!tag.closing) return null
                closed = true
                break
            }
            if (tag.name == "li") {
                if (tag.closing) {
                    if (liDepth > 0) liDepth--
                    if (liDepth == 0 && slot != null) {
                        val selected = candidates.firstNotNullOfOrNull(::image) ?: return null
                        if (items.put(slot, selected) != null) return null
                        slot = null
                    }
                } else {
                    if (liDepth == 0) {
                        slot = tag.attributes["slot"]?.let { Regex("page-([0-9]+)").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
                        candidates = mutableListOf()
                    }
                    liDepth++
                }
            }
            if (!tag.closing && tag.name == "img" && slot != null) {
                listOfNotNull(tag.attributes["src"], tag.attributes["data-lazy-src"])
                    .filterNot { it.startsWith("data:") }.forEach(candidates::add)
            }
        }
        if (!closed || items.isEmpty() || items.keys.toList() != (1..items.size).toList()) return null
        return items.values.distinct().take(MAX_MEDIA)
    }

    private fun image(raw: String): String? {
        val uri = https(raw) ?: return null
        if (uri.host !in setOf("i.redd.it", "preview.redd.it")) return null
        val normalized = RedditLinks.normalize(raw)
        val original = https(normalized) ?: return null
        if (original.host != "i.redd.it" || !original.rawPath.matches(Regex("""/[A-Za-z0-9]+\.(?:png|jpe?g|gif|webp)""", RegexOption.IGNORE_CASE))) return null
        return "https://i.redd.it${original.rawPath}"
    }

    private fun https(raw: String): URI? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) return null
        if (!UrlValidator.isAllowedHttps(raw)) throw ExtractionException(ExtractionError.UnsupportedUrl, UnsafeNetworkTargetException())
        return uri.takeIf { it.host != null }
    }

    private fun redditHost(host: String) = host.equals("reddit.com", ignoreCase = true) || host.lowercase().endsWith(".reddit.com")

    private data class Tag(val name: String, val attributes: Map<String, String>, val closing: Boolean)
    private fun tags(html: String): List<Tag> = TOKENS.findAll(html).mapNotNull { match ->
        val name = match.groupValues[2].lowercase().takeIf(String::isNotEmpty) ?: return@mapNotNull null
        Tag(name, ATTRIBUTES.findAll(match.groupValues[3]).associate {
            it.groupValues[1].lowercase() to unescape(it.groupValues[2].ifEmpty { it.groupValues[3] })
        }, match.value.startsWith("</"))
    }.toList()

    private fun unescape(text: String): String = ENTITY.replace(text) { match ->
        val name = match.groupValues[1]
        val code = when {
            name.startsWith("#x", true) -> name.substring(2).toIntOrNull(16)
            name.startsWith('#') -> name.substring(1).toIntOrNull()
            else -> null
        }
        if (code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF) String(Character.toChars(code))
        else ENTITIES[name] ?: match.value
    }

    private val TOKENS = Regex("""<!--.*?-->|<(script|style)\b[^>]*>.*?</\1\s*>|</?([a-zA-Z][\w:-]*)((?:"[^"]*"|'[^']*'|[^'">])*)>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ATTRIBUTES = Regex("""\s([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[A-Za-z]+);")
    private val ENTITIES = mapOf("amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "nbsp" to "\u00a0",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "copy" to "©", "reg" to "®")
}
