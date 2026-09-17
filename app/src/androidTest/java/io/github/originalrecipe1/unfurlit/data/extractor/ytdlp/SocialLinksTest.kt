package io.github.originalrecipe1.unfurlit.data.extractor.ytdlp

import androidx.test.platform.app.InstrumentationRegistry
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Explicitly opted in: real sites can block CI or change their content at any time. */
@RunWith(Parameterized::class)
class SocialLinksTest(private val id: String, private val url: String, private val expected: String) {
    @Test(timeout = 150_000)
    fun extractsExpectedMediaOrReportsExpectedFailure() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveLinks") == "true")
        val extractor = YtDlpMediaExtractor(InstrumentationRegistry.getInstrumentation().targetContext)
        // Never include the extraction result: it contains temporary playback credentials.
        val actual = try {
            val result = runBlocking { extractor.extract(url) }
            assertTrue("$id returned no media", result.media.isNotEmpty())
            "success"
        } catch (error: ExtractionException) {
            error.error.javaClass.simpleName
        }
        assertEquals("$id: extraction outcome", expected, actual)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun links(): List<Array<String>> {
            val json = InstrumentationRegistry.getInstrumentation().context.assets
                .open("social-links.json").bufferedReader().use { it.readText() }
            val cases = JSONArray(json)
            return (0 until cases.length()).map { index ->
                val case = cases.getJSONObject(index)
                arrayOf(case.getString("id"), case.getString("url"), case.getString("expected"))
            }
        }
    }
}
