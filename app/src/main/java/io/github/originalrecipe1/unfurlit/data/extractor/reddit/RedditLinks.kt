package io.github.originalrecipe1.unfurlit.data.extractor.reddit

import java.net.URI
import java.net.URLDecoder

/**
 * Rewrites Reddit link variants that the extractors do not recognize into the
 * canonical Reddit URL for the same post or image:
 *
 * - posts on alternative front ends such as eddrit or Redlib instances, which keep
 *   Reddit's `/r/<subreddit>/comments/<id>` paths on their own host;
 * - `reddit.com/media?url=…` wrappers from Reddit's "copy image link";
 * - resized `preview.redd.it` images, whose file name may carry the post title
 *   (`title-v0-<id>.png`), are mapped to the original on `i.redd.it`.
 *
 * Anything else is returned unchanged. Only the extraction URL changes; history
 * and "Open link" keep the link the user shared.
 */
internal object RedditLinks {
    fun normalize(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return url
        val path = uri.rawPath.orEmpty()

        if (host.isReddit() && path.trimEnd('/') == "/media") {
            val inner = uri.rawQuery?.queryParameter("url") ?: return url
            val innerHost = runCatching { URI(inner).host?.lowercase() }.getOrNull()
            return if (innerHost in REDDIT_IMAGE_HOSTS) normalize(inner) else url
        }
        if (host == "preview.redd.it") {
            val original = path.substringAfterLast('/').substringAfterLast(TITLE_SEPARATOR)
            return if (IMAGE_NAME.matches(original)) "https://i.redd.it/$original" else url
        }
        if (!host.isReddit() && POST_PATH.matches(path)) {
            return "https://www.reddit.com$path"
        }
        return url
    }

    /**
     * Reddit redirects clients without a session from a post to pages such as its
     * over-18 or login gate. When a post URL [requested] lands on another Reddit page,
     * extraction uses the post itself; any other redirect target is kept.
     */
    fun keepPostOverGate(requested: String, resolved: String): String {
        if (requested == resolved || !requested.isRedditPost()) return resolved
        val resolvedHost = runCatching { URI(resolved).host?.lowercase() }.getOrNull() ?: return resolved
        return if (resolvedHost.isReddit() && !resolved.isRedditPost()) requested else resolved
    }

    private fun String.isRedditPost(): Boolean {
        val uri = runCatching { URI(this) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.rawPath.orEmpty()
        return host.isReddit() && (POST_PATH.matches(path) || SHORT_POST_PATH.matches(path))
    }

    /**
     * The title Reddit puts in a post URL's slug, e.g. `…/comments/1wop7o6/what_happened_to_orlando_bloom/`
     * gives "What happened to orlando bloom". Null for other URLs or an empty slug.
     */
    fun titleFromPostUrl(url: String): String? {
        val path = runCatching { URI(url).path }.getOrNull() ?: return null
        val slug = POST_SLUG.matchEntire(path)?.groupValues?.get(1) ?: return null
        return slug.replace('_', ' ').trim().takeIf(String::isNotEmpty)
            ?.replaceFirstChar { it.uppercaseChar() }
            ?.take(MAX_TITLE_LENGTH)
    }

    private fun String.isReddit() = this == "reddit.com" || endsWith(".reddit.com")

    private fun String.queryParameter(name: String): String? = split('&')
        .map { it.split('=', limit = 2) }
        .firstOrNull { it.size == 2 && it[0] == name }
        ?.let { runCatching { URLDecoder.decode(it[1], "UTF-8") }.getOrNull() }

    private const val TITLE_SEPARATOR = "-v0-"
    private val REDDIT_IMAGE_HOSTS = setOf("i.redd.it", "preview.redd.it")
    private val IMAGE_NAME = Regex("[A-Za-z0-9]+\\.(?:png|jpe?g|gif|webp)", RegexOption.IGNORE_CASE)
    private const val MAX_TITLE_LENGTH = 512
    private val POST_SLUG = Regex("/r/[A-Za-z0-9_]{2,21}/comments/[a-z0-9]+/([^/]+)/?")
    private val SHORT_POST_PATH = Regex("/(?:comments|gallery)/[a-z0-9]+(?:/[^?#]*)?")
    private val POST_PATH = Regex("/r/[A-Za-z0-9_]{2,21}/comments/[a-z0-9]+(?:/[^?#]*)?")
}
