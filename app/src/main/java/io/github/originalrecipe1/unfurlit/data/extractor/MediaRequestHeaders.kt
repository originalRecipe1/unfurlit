package io.github.originalrecipe1.unfurlit.data.extractor

/**
 * Filters request headers that an extractor asks the app to send when loading
 * media. Hop-by-hop, proxy, and client-address headers are never forwarded.
 */
internal object MediaRequestHeaders {
    fun sanitize(headers: Sequence<Pair<String, String?>>): Map<String, String> =
        headers
            .take(MAX_HEADER_COUNT)
            .mapNotNull { (name, value) ->
                if (name.matches(HEADER_NAME) &&
                    name.lowercase() !in BLOCKED_HEADERS &&
                    value != null &&
                    value.length <= MAX_HEADER_LENGTH &&
                    '\r' !in value &&
                    '\n' !in value
                ) {
                    name to value
                } else {
                    null
                }
            }
            .toMap()

    private const val MAX_HEADER_COUNT = 32
    private const val MAX_HEADER_LENGTH = 8 * 1024
    private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val BLOCKED_HEADERS = setOf(
        "connection",
        "content-length",
        "forwarded",
        "host",
        "proxy-authorization",
        "proxy-connection",
        "range",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
        "x-forwarded-for",
        "x-real-ip",
    )
}
