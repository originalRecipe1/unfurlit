package io.github.originalrecipe1.unfurlit.data.extractor

import java.net.URI

/**
 * Sites Unfurlit refuses before any network request. Reddit requires apps to sign in,
 * so its pages and its media hosts are all rejected rather than left to fail slowly.
 */
internal object UnsupportedSites {
    fun matches(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase()?.trimEnd('.') ?: return false
        return DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    private val DOMAINS = listOf("reddit.com", "redd.it", "redditmedia.com")
}
