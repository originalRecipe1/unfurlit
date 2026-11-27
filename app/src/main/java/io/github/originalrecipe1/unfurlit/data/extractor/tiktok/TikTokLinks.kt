package io.github.originalrecipe1.unfurlit.data.extractor.tiktok

import java.net.URI

/**
 * Rewrites TikTok share links whose path carries an obfuscated, non-numeric post ID
 * (as Messenger and some other apps produce, e.g. `/@user/video/e1ml5fsz9al5hq?…`)
 * into the canonical numeric URL taken from the `share_item_id` query parameter.
 * The extractors only recognize numeric IDs.
 *
 * Anything else is returned unchanged. Only the extraction URL changes; history
 * and "Open link" keep the link the user shared.
 */
internal object TikTokLinks {
    fun normalize(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return url
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return url
        if (host !in HOSTS) return url
        val match = POST_PATH.matchEntire(uri.rawPath.orEmpty()) ?: return url
        val (user, kind, id) = match.destructured
        if (NUMERIC_ID.matches(id)) return url
        val shareItemId = uri.rawQuery?.queryParameter("share_item_id") ?: return url
        if (!NUMERIC_ID.matches(shareItemId)) return url
        return "https://www.tiktok.com/@$user/$kind/$shareItemId"
    }

    private fun String.queryParameter(name: String): String? = split('&')
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")

    private val HOSTS = setOf("tiktok.com", "www.tiktok.com", "m.tiktok.com")
    private val POST_PATH = Regex("/@([A-Za-z0-9_.-]+)/(video|photo)/([A-Za-z0-9]+)/?")
    private val NUMERIC_ID = Regex("[0-9]{1,25}")
}
