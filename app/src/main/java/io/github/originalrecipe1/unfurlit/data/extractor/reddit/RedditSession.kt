package io.github.originalrecipe1.unfurlit.data.extractor.reddit

import android.os.SystemClock
import io.github.originalrecipe1.unfurlit.data.network.PublicPageLoader
import io.github.originalrecipe1.unfurlit.data.network.SafeHttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** Anonymous page cookies only. The shared instance is never installed on a playback/engine client. */
internal class RedditSession(
    private val bootstrap: suspend () -> List<Cookie> = ::bootstrapCookies,
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
    private val wallMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var cached: Lease? = null

    suspend fun get(): Lease = mutex.withLock {
        cached?.takeIf(::fresh) ?: create()
    }

    /** A late challenge from an older lease must not discard another extraction's new session. */
    suspend fun rebuild(failed: Lease): Lease = mutex.withLock {
        cached?.takeIf { it !== failed && fresh(it) } ?: run {
            cached = null
            create()
        }
    }

    suspend fun invalidate(failed: Lease) = mutex.withLock {
        if (cached === failed) cached = null
    }

    private fun fresh(lease: Lease) = elapsedMillis() - lease.createdAt < MAX_AGE_MILLIS &&
        lease.cookies.any { it.expiresAt > wallMillis() }

    private suspend fun create(): Lease {
        val cookies = bootstrap().filter { redditDomain(it.domain) && it.expiresAt > wallMillis() }
        return Lease(elapsedMillis(), cookies.toList(), wallMillis).also { cached = it }
    }

    internal class Lease(
        internal val createdAt: Long,
        internal val cookies: List<Cookie>,
        private val wallMillis: () -> Long,
    ) {
        fun cookieHeader(url: String): String? {
            val target = runCatching { url.toHttpUrl() }.getOrNull()?.takeIf(::redditUrl) ?: return null
            return cookies.filter { it.expiresAt > wallMillis() && it.matches(target) }
                .joinToString("; ") { "${it.name}=${it.value}" }.takeIf(String::isNotEmpty)
        }
        override fun toString() = "RedditSession.Lease(<redacted>)"
    }

    companion object {
        const val MAX_AGE_MILLIS = 30 * 60 * 1_000L
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
        val shared: RedditSession by lazy { RedditSession() }

        fun pageRequest(url: String): Request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Sec-Fetch-Mode", "navigate").build()

        private fun redditDomain(domain: String) = domain == "reddit.com" || domain.endsWith(".reddit.com")
        private fun redditUrl(url: HttpUrl) = url.isHttps && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && redditDomain(url.host)

        private suspend fun bootstrapCookies(): List<Cookie> {
            // This jar belongs to this one bootstrap only; it cannot see user/browser cookies.
            val collected = mutableListOf<Cookie>()
            val jar = object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    if (!redditUrl(url)) return
                    cookies.filter { redditDomain(it.domain) }.forEach { cookie ->
                        collected.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                        collected.add(cookie)
                    }
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    if (!redditUrl(url)) emptyList() else collected.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
            }
            val client = SafeHttpClient.preflight.newBuilder().cookieJar(jar).build()
            PublicPageLoader.load(pageRequest("https://old.reddit.com/"), client, acceptForbidden = true)
            return collected.toList()
        }
    }
}
