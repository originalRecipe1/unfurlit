package io.github.originalrecipe1.unfurlit.data.extractor.reddit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.Cookie
import org.junit.Assert.*
import org.junit.Test

class RedditSessionTest {
    private var elapsed = 0L
    private var wall = 1_000L
    private var creations = 0
    private fun session() = RedditSession({ listOf(cookie("session${++creations}")) }, { elapsed }, { wall })
    private fun cookie(value: String) = Cookie.Builder().name("loid").value(value).domain("reddit.com").path("/")
        .secure().expiresAt(wall + 86_400_000).build()

    @Test fun createsLazilyReusesAndExpiresAtThirtyMinutes() = runBlocking {
        val session = session()
        assertEquals(0, creations)
        val first = session.get()
        assertSame(first, session.get())
        elapsed = RedditSession.MAX_AGE_MILLIS - 1
        assertSame(first, session.get())
        elapsed++
        assertNotSame(first, session.get())
        assertEquals(2, creations)
    }

    @Test fun scopesCookiesToHttpsRedditOnlyAndExpiresCookieValues() = runBlocking {
        val session = session()
        val lease = session.get()
        assertEquals("loid=session1", lease.cookieHeader("https://www.reddit.com/comments/abc"))
        assertEquals("loid=session1", lease.cookieHeader("https://old.reddit.com/"))
        for (url in listOf("http://www.reddit.com/", "https://reddit.com:8443/", "https://reddit.com.evil.example/",
            "https://v.redd.it/id/file.m3u8", "https://i.redd.it/image.jpg", "https://preview.redd.it/image.jpg", "https://streamable.com/id")) {
            assertNull(url, lease.cookieHeader(url))
        }
        assertFalse(lease.toString().contains("session1"))
        wall += 86_400_001
        assertNull(lease.cookieHeader("https://www.reddit.com/"))
        assertNotSame(lease, session.get())
    }

    @Test fun concurrentCreationAndRebuildEachHaveOneInFlightBootstrap() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = RedditSession({
            creations++
            started.complete(Unit)
            release.await()
            listOf(cookie("anonymous"))
        }, { elapsed }, { wall })
        val pending = (1..12).map { async { session.get() } }
        started.await()
        yield()
        assertEquals(1, creations)
        release.complete(Unit)
        val leases = pending.awaitAll()
        leases.forEach { assertSame(leases.first(), it) }
        val rebuilt = (1..12).map { async { session.rebuild(leases.first()) } }.awaitAll()
        assertEquals(2, creations)
        rebuilt.forEach { assertSame(rebuilt.first(), it) }
        session.invalidate(leases.first()) // A late old response cannot discard the new lease.
        assertSame(rebuilt.first(), session.get())
        session.invalidate(rebuilt.first())
        assertNotSame(rebuilt.first(), session.get())
        assertEquals(3, creations)
    }
}
