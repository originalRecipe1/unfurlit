package io.github.originalrecipe1.unfurlit.data.extractor.reddit

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originalrecipe1.unfurlit.data.extractor.ytdlp.YtDlpMediaExtractor
import io.github.originalrecipe1.unfurlit.data.network.SafeHttpClient
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.linkcheck.SocialLinkCase
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in device proof for the two new single-image fixtures, including media bytes. */
class RedditAdapterDeviceTest {
    @Test(timeout = 30_000)
    fun derivedDashIsReachableWithoutRedditCookies() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("redditAdapter") == "true")
        val html = "<shreddit-post id='t3_90bu6w' content-href='https://v.redd.it/gyh95hiqc0b11'></shreddit-post>"
        val post = RedditPostParser.parse("https://www.reddit.com/r/aww/comments/90bu6w/", html)!!
        val manifest = (post.content as RedditContent.Video).manifest
        assertTrue(RedditPostParser.isManifestUrl(manifest))
        val request = Request.Builder().url(manifest).build()
        assertNull(request.header("Cookie"))
        SafeHttpClient.preflight.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            val body = response.peekBody(64 * 1024).string()
            assertTrue(body.contains("<MPD"))
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("redditManifest", JSONObject().put("httpStatus", response.code).put("type", "DASH").toString())
            })
        }
    }

    @Test(timeout = 120_000)
    fun extractsAndFetchesSingleImagesWithoutRedditCookies() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("redditAdapter") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cases = instrumentation.context.assets.open("social-links.json").bufferedReader().use {
            SocialLinkCase.parseAll(it.readText())
        }.filter { it.id in setOf("reddit-image-earthporn", "reddit-image-pics") }
        assertEquals(2, cases.size)
        val extractor = YtDlpMediaExtractor(instrumentation.targetContext)
        for (case in cases) {
            val result = extractor.extract(case.url)
            assertEquals(case.url, result.sourceUrl)
            assertEquals("Reddit", result.platform)
            assertFalse(result.title.isNullOrBlank())
            assertFalse(result.author.isNullOrBlank())
            val image = result.media.single() as ExtractedMedia.Image
            assertTrue(image.source.cookies.isEmpty())
            assertTrue(image.source.headers.keys.none { it.equals("Cookie", ignoreCase = true) })
            val request = Request.Builder().url(image.source.url).header("Range", "bytes=0-31").build()
            SafeHttpClient.preflight.newCall(request).execute().use { response ->
                assertTrue(response.isSuccessful)
                assertTrue(response.header("Content-Type").orEmpty().startsWith("image/"))
                val bytes = response.body!!.source().readByteArray(12)
                val jpeg = bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()
                val png = bytes[0] == 0x89.toByte() && String(bytes, 1, 3, Charsets.US_ASCII) == "PNG"
                val gif = String(bytes, 0, 3, Charsets.US_ASCII) == "GIF"
                val webp = String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
                assertTrue(jpeg || png || gif || webp)
                val summary = JSONObject().put("case", case.id).put("httpStatus", response.code)
                    .put("mediaCount", result.media.size).put("validImageSignature", true)
                instrumentation.sendStatus(2, Bundle().apply { putString("redditImage", summary.toString()) })
            }
        }
    }
}
