package io.github.originalrecipe1.unfurlit.data.extractor.reddit

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originalrecipe1.unfurlit.BuildConfig
import io.github.originalrecipe1.unfurlit.data.network.PublicPageLoader
import io.github.originalrecipe1.unfurlit.data.network.SafeHttpClient
import io.github.originalrecipe1.unfurlit.data.network.UnsafeNetworkTargetException
import io.github.originalrecipe1.unfurlit.data.network.UrlPreflight
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.linkcheck.SocialLinkCase
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.IOException

/** Opt-in phone experiment only: no engine calls, persisted cookies or production changes. */
@RunWith(Parameterized::class)
class RedditTransportSpikeTest(
    private val id: String,
    private val mode: String,
    private val case: SocialLinkCase,
) {
    @Test(timeout = 100_000)
    fun receivesRequestedPost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("redditTransport") == "true")
        assumeTrue(BuildConfig.DEBUG)
        val network = arguments.getString("redditTransportNetwork")
        require(network in setOf("wifi", "mobile")) { "Set redditTransportNetwork to wifi or mobile" }
        val actualNetwork = activeNetwork()
        val report = JSONObject().put("case", id).put("mode", mode)
            .put("network", actualNetwork).put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
        val start = SystemClock.elapsedRealtime()
        var outcome = "REQUEST_FAILED"
        var mediaChecksPass = true
        val jsonMode = mode == "desktop-session-json"
        try {
            check(actualNetwork == network) { "Requested network is not active" }
            // Exactly the production normalization/preflight/gate selection, including mirrors.
            val normalized = RedditLinks.normalize(case.url)
            val resolved = runBlocking { UrlPreflight().resolve(normalized) }
            val pageUrl = RedditLinks.keepPostOverGate(normalized, resolved).toHttpUrl()
            val requestedId = postId(normalized.toHttpUrl()) ?: postId(pageUrl)
            report.put("requestedPostId", requestedId)
                .put("resolvedPostId", postId(pageUrl))
                .put("preflightChangedUrl", resolved != normalized)
            if (requestedId == null || !redditHost(pageUrl)) {
                outcome = "UNRESOLVED_POST"
            } else {
                val agent = if (mode.startsWith("session-mobile-ua")) MOBILE_UA else DESKTOP_UA
                var request = request(pageUrl, agent)
                if (mode != "desktop") {
                    val jar = SessionCookies()
                    val bootstrapClient = SafeHttpClient.preflight.newBuilder().cookieJar(jar).build()
                    bootstrapClient.newCall(request("https://old.reddit.com/".toHttpUrl(), agent))
                        .execute().use { response ->
                            readBounded(response.body?.source())
                            report.put("bootstrapStatus", response.code)
                        }
                    if (mode == "session-mobile-ua-over18") {
                        jar.saveFromResponse("https://www.reddit.com/".toHttpUrl(), listOf(
                            Cookie.Builder().name("over18").value("1").domain("reddit.com").path("/").secure().build(),
                        ))
                    }
                    val target = if (jsonMode) jsonUrl(pageUrl) else pageUrl
                    val cookies = jar.loadForRequest(target)
                    report.put("scopedCookieCount", cookies.size)
                        .put("hasLoid", cookies.any { it.name == "loid" })
                        .put("hasOver18", cookies.any { it.name == "over18" && it.value == "1" })
                    request = request.newBuilder().url(target).apply {
                        if (cookies.isNotEmpty()) header("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
                    }.build()
                }
                if (jsonMode) {
                    // Same client and decompressed-byte/call limits as PublicPageLoader;
                    // observe the actual status/body together, including non-2xx JSON errors.
                    val response = fetch(request, requestedId, json = true)
                    report.put("page", response)
                    outcome = response.getString("outcome")
                } else {
                    try {
                        val html = runBlocking { PublicPageLoader.load(request) }
                        report.put("page", describe(html, requestedId))
                        outcome = classifyHtml(html, requestedId)
                        if (outcome != "POST_HTML") {
                            // Production loader does not expose its final URL. This is a
                            // second response, labelled separately, with no query recorded.
                            report.put("failureDiagnostic", fetch(request, requestedId, json = false))
                        }
                        val post = requestedPost(html, requestedId)
                        if (id in setOf("reddit-image-post", "reddit-mirror-post") && post != null) {
                            val href = attributes(post).get("content-href")
                            report.put("singleImageHref", imageUrl(href) != null)
                        }
                        if (mode == "session-mobile-ua" && id in setOf("reddit-gallery", "reddit-gallery-share")) {
                            mediaChecksPass = false
                            val gallery = gallerySummary(post, case.count)
                            report.put("gallery", gallery)
                            mediaChecksPass = gallery.getBoolean("matchesFixture") && gallery.getBoolean("allMappable") &&
                                gallery.getBoolean("sequentialOrder") && gallery.getBoolean("distinctImages")
                        }
                        if (mode == "session-mobile-ua" && id == "reddit-native-video") {
                            mediaChecksPass = false
                            val manifest = manifestSummary(post)
                            report.put("manifest", manifest)
                            mediaChecksPass = manifest.optInt("status") == 200 && manifest.optString("type") in setOf("HLS", "DASH")
                        }
                    } catch (error: ExtractionException) {
                        report.put("loaderError", error.error.javaClass.simpleName)
                        if (generateSequence<Throwable>(error) { it.cause }.none { it is UnsafeNetworkTargetException }) {
                            // Separate response only; it cannot turn a failed loader call into a pass.
                            report.put("diagnostic", fetch(request, requestedId, json = false))
                        }
                        outcome = "LOADER_FAILED"
                    }
                }
            }
        } catch (error: IOException) {
            report.put("error", error.javaClass.simpleName)
        } catch (error: IllegalStateException) {
            report.put("error", error.javaClass.simpleName)
        }
        report.put("outcome", outcome).put("elapsedMillis", SystemClock.elapsedRealtime() - start)
        val summary = report.toString()
        Log.i("RedditTransportSpike", summary)
        instrumentation.sendStatus(2, Bundle().apply { putString("redditTransport", summary) })
        assertEquals("$id/$mode: $summary", if (jsonMode) "POST_JSON" else "POST_HTML", outcome)
        assertTrue("Media checks: $summary", mediaChecksPass)
    }

    private fun fetch(request: Request, postId: String, json: Boolean): JSONObject =
        SafeHttpClient.preflight.newCall(request).execute().use { response ->
            val body = readBounded(response.body?.source())
            val result = describe(body, postId).put("httpStatus", response.code)
                .put("contentType", response.header("Content-Type"))
                .put("finalPath", response.request.url.encodedPath)
            val classification = if (json) classifyJson(body, postId) else classifyHtml(body, postId)
            result.put("outcome", if (response.code == 200 || classification != "POST_JSON") classification else "HTTP_ERROR")
        }

    private fun readBounded(source: okio.BufferedSource?): String {
        check(source != null) { "Missing body" }
        source.request(PublicPageLoader.MAX_PAGE_BYTES.toLong() + 1)
        check(source.buffer.size <= PublicPageLoader.MAX_PAGE_BYTES) { "Page exceeds production limit" }
        return source.readUtf8()
    }

    private fun classifyJson(body: String, requestedId: String): String {
        val listings = runCatching { JSONArray(body) }.getOrNull()
            ?: return if (isBlock(body)) "SECURITY_BLOCK" else "NOT_JSON_ARRAY"
        val children = listings.optJSONObject(0)?.optJSONObject("data")?.optJSONArray("children")
            ?: return "NO_POST_LISTING"
        val post = (0 until children.length()).mapNotNull { children.optJSONObject(it) }
            .firstOrNull { it.optString("kind") == "t3" && it.optJSONObject("data")?.optString("id") == requestedId }
            ?.optJSONObject("data") ?: return "REQUESTED_POST_ABSENT"
        val video = post.optJSONObject("secure_media")?.optJSONObject("reddit_video")
        val usableVideo = listOf("dash_url", "hls_url").any { usableHttps(video?.optString(it)) }
        val usableDestination = usableHttps(post.optString("url_overridden_by_dest"))
        val metadata = post.optJSONObject("media_metadata")
        val usableGallery = post.optJSONObject("gallery_data")?.optJSONArray("items")?.let { items ->
            items.length() > 0 && (0 until items.length()).all { index ->
                val mediaId = items.optJSONObject(index)?.optString("media_id").orEmpty()
                val media = metadata?.optJSONObject(mediaId)
                val source = media?.optJSONObject("s")
                media?.optString("status") == "valid" &&
                    listOf("u", "gif", "mp4").any { usableHttps(source?.optString(it)) }
            }
        } == true
        return if (usableVideo || usableDestination || usableGallery) "POST_JSON" else "POST_WITHOUT_USABLE_MEDIA"
    }

    private fun usableHttps(value: String?): Boolean = runCatching {
        val url = value?.replace("&amp;", "&")?.toHttpUrl() ?: return@runCatching false
        url.isHttps && url.host.isNotBlank()
    }.getOrDefault(false)

    private fun classifyHtml(html: String, postId: String): String {
        if (isBlock(html)) return "SECURITY_BLOCK"
        val post = requestedPost(html, postId) ?: return "UNRECOGNISED_HTML"
        return if (!attributes(post).get("content-href").isNullOrBlank()) "POST_HTML" else "POST_WITHOUT_MEDIA_LINK"
    }

    /** Include only the matching post's balanced subtree, never recommendations. */
    private fun requestedPost(html: String, postId: String): String? {
        val start = Regex(
            """<shreddit-post(?=[\s>])[^>]*\sid\s*=\s*["']t3_${Regex.escape(postId)}["'][^>]*>""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.range?.first ?: return null
        var depth = 0
        for (tag in Regex("""</?shreddit-post(?=[\s>])[^>]*>""", RegexOption.IGNORE_CASE).findAll(html, start)) {
            depth += if (tag.value.startsWith("</")) -1 else 1
            if (depth == 0) return html.substring(start, tag.range.last + 1)
        }
        return null
    }

    private fun attributes(tag: String): Map<String, String> =
        Regex("""\s([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""").findAll(tag.substringBefore('>') + ">")
            .associate { match ->
                match.groupValues[1].lowercase() to android.text.Html.fromHtml(
                    match.groupValues[2].ifEmpty { match.groupValues[3] }, android.text.Html.FROM_HTML_MODE_LEGACY,
                ).toString()
            }

    private fun imageUrl(value: String?): HttpUrl? = runCatching {
        val normalized = value?.let(RedditLinks::normalize)?.toHttpUrl() ?: return@runCatching null
        normalized.takeIf { it.isHttps && it.host == "i.redd.it" &&
            it.encodedPath.matches(Regex("""/[A-Za-z0-9]+\.(?:png|jpe?g|gif|webp)""", RegexOption.IGNORE_CASE)) }
            ?.newBuilder()?.query(null)?.fragment(null)?.build()
    }.getOrNull()

    private fun gallerySummary(post: String?, expected: Int?): JSONObject {
        val carousel = post?.let {
            Regex("""<gallery-carousel\b[^>]*>(.*?)</gallery-carousel\s*>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(it)?.groupValues?.get(1)
        }
        val slots = mutableListOf<Int>()
        val images = mutableListOf<HttpUrl?>()
        if (carousel != null) {
            for (item in Regex("""<li\b([^>]*)>(.*?)</li\s*>""",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(carousel)) {
                val slot = attributes("<li ${item.groupValues[1]}>")["slot"]
                    ?.let { Regex("page-([0-9]+)").matchEntire(it)?.groupValues?.get(1)?.toInt() } ?: continue
                slots.add(slot)
                val candidates = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(item.groupValues[2])
                    .flatMap { tag -> attributes(tag.value).let { listOf(it["src"], it["data-lazy-src"]) } }
                    .mapNotNull(::imageUrl).toList()
                images.add(candidates.firstOrNull())
            }
        }
        val found = images.filterNotNull()
        return JSONObject().put("carouselItemCount", slots.size).put("imageCount", found.size)
            .put("allMappable", images.isNotEmpty() && images.all { it != null })
            .put("sequentialOrder", slots.isNotEmpty() && slots == (1..slots.size).toList())
            .put("distinctImages", found.isNotEmpty() && found.distinct().size == found.size)
            .put("matchesFixture", expected != null && slots.size == expected && found.size == expected)
    }

    private fun manifestSummary(post: String?): JSONObject {
        val player = post?.let { Regex("""<shreddit-player\b[^>]*>""", RegexOption.IGNORE_CASE).find(it)?.value }
        val explicit = player?.let { attributes(it)["src"] }?.let { runCatching { it.toHttpUrl() }.getOrNull() }
            ?.takeIf { it.isHttps && it.host == "v.redd.it" &&
                (it.encodedPath.endsWith(".m3u8") || it.encodedPath.endsWith(".mpd")) }
        val content = post?.let { attributes(it)["content-href"] }?.let { runCatching { it.toHttpUrl() }.getOrNull() }
            ?.takeIf { it.isHttps && it.host == "v.redd.it" && it.encodedPath.matches(Regex("/[A-Za-z0-9]+/?")) }
        val url = explicit ?: content?.newBuilder()
            ?.encodedPath(content.encodedPath.trimEnd('/') + "/DASHPlaylist.mpd")?.query(null)?.build()
            ?: return JSONObject().put("status", 0).put("type", "NONE")
        // New request and production client with no cookie jar: never reuse the Reddit request.
        val request = Request.Builder().url(url).build()
        check(request.header("Cookie") == null)
        return SafeHttpClient.preflight.newCall(request).execute().use { response ->
            val body = readBounded(response.body?.source())
            val type = when {
                body.trimStart().startsWith("#EXTM3U") -> "HLS"
                Regex("""<(?:[\w]+:)?MPD\b""").containsMatchIn(body) -> "DASH"
                else -> "UNKNOWN"
            }
            JSONObject().put("status", response.code).put("type", type)
        }
    }

    private fun isBlock(body: String) = BLOCK_MARKERS.any { body.contains(it, ignoreCase = true) }

    private fun describe(body: String, postId: String): JSONObject = JSONObject()
        .put("bytes", body.toByteArray(Charsets.UTF_8).size)
        .put("hasShredditPost", body.contains("<shreddit-post", ignoreCase = true))
        .put("hasRequestedPostId", body.contains("t3_$postId"))
        .put("hasAnyT3Id", Regex("t3_[a-z0-9]+", RegexOption.IGNORE_CASE).containsMatchIn(body))
        .put("hasChallengeMarker", body.contains("challenge", ignoreCase = true))
        .put("title", Regex("""<title\b[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)?.take(120))
        .put("over18GateMarkers", Regex("over.?18|age.verification|mature content|adult content", RegexOption.IGNORE_CASE).containsMatchIn(body))
        .put("nsfwMarkers", body.contains("nsfw", ignoreCase = true))
        .put("loginPromptMarkers", Regex("log in to (?:view|continue|see)|sign in to (?:view|continue|see)|must be logged in|login.wall|shreddit-login", RegexOption.IGNORE_CASE).containsMatchIn(body))
        .put("removedMarkers", Regex("(?:post|content) (?:has been|was) removed|removed by (?:moderator|reddit)", RegexOption.IGNORE_CASE).containsMatchIn(body))
        .put("deletedMarkers", Regex("""(?:post|content|account) (?:has been|was) deleted|\[deleted\]""", RegexOption.IGNORE_CASE).containsMatchIn(body))
        .put("quarantineMarkers", body.contains("quarantin", ignoreCase = true))
        .put("notFoundMarkers", Regex("Page not found|shreddit-forbidden-not_found", RegexOption.IGNORE_CASE).containsMatchIn(body))
        .put("forbiddenContainer", body.contains("shreddit-forbidden", ignoreCase = true))
        .put("bannedCommunityMarkers", Regex("community (?:has been|is) banned|has been banned|banned-subreddit", RegexOption.IGNORE_CASE).containsMatchIn(body))
        .put("privateCommunityMarkers", Regex("community is private|private community|private subreddit", RegexOption.IGNORE_CASE).containsMatchIn(body))

    private fun activeNetwork(): String {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys connectivity").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            }
        val active = Regex("Active default network: ([0-9]+)").find(output)?.groupValues?.get(1)
            ?: return "unknown"
        val agent = output.lineSequence().firstOrNull { it.contains("NetworkAgentInfo{network{$active}") }
            ?: return "unknown"
        return when {
            agent.contains("Transports: WIFI") -> "wifi"
            agent.contains("Transports: CELLULAR") -> "mobile"
            else -> "other"
        }
    }

    private class SessionCookies : CookieJar {
        private val cookies = mutableListOf<Cookie>()
        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (!redditHost(url)) return
            for (cookie in cookies) {
                this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (cookie.expiresAt > System.currentTimeMillis()) this.cookies.add(cookie)
            }
        }
        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            if (!redditHost(url)) emptyList() else cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
    }

    companion object {
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
        private const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
        private val BLOCK_MARKERS = listOf("blocked by network security", "your request has been blocked", "whoa there, pardner")
        private val VIDEO_IDS = setOf("reddit-video", "reddit-native-video", "reddit-external-imgur", "reddit-external-streamable")
        private val POST_IDS = VIDEO_IDS + setOf("reddit-image-post", "reddit-gallery", "reddit-gallery-comments",
            "reddit-gallery-share", "reddit-gallery-short", "reddit-mirror-post")

        private fun redditHost(url: HttpUrl) = url.isHttps && (url.host == "reddit.com" || url.host.endsWith(".reddit.com"))
        private fun request(url: HttpUrl, agent: String) = Request.Builder().url(url)
            .header("User-Agent", agent).header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5").header("Sec-Fetch-Mode", "navigate").build()
        private fun postId(url: HttpUrl): String? = Regex("""/(?:comments|gallery)/([a-z0-9]+)(?:/|$)""")
            .find(url.encodedPath)?.groupValues?.get(1)
            ?: url.pathSegments.firstOrNull()?.takeIf { url.host == "redd.it" && it.matches(Regex("[a-z0-9]+")) }
        // Append to the path only; HttpUrl preserves query parameters and their encoding.
        private fun jsonUrl(url: HttpUrl) = url.newBuilder().encodedPath(url.encodedPath + ".json").build()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}/{1}")
        fun links(): List<Array<Any>> {
            val fixtures = InstrumentationRegistry.getInstrumentation().context.assets
                .open("social-links.json").bufferedReader().use { it.readText() }
            val cases = SocialLinkCase.parseAll(fixtures).filter { it.id in POST_IDS }
            require(cases.map { it.id }.toSet() == POST_IDS) { "Missing Reddit post fixtures" }
            val modes = listOf("desktop", "desktop-session", "desktop-session-json", "session-mobile-ua", "session-mobile-ua-over18")
            val selected = InstrumentationRegistry.getArguments().getString("redditTransportModes")
                ?.split(',')?.map(String::trim) ?: modes
            require(selected.isNotEmpty() && selected.all { it in modes }) { "Unknown spike mode" }
            return selected.flatMap { mode ->
                cases.filter { mode != "desktop" || it.id in VIDEO_IDS }.map { arrayOf<Any>(it.id, mode, it) }
            }
        }
    }
}
