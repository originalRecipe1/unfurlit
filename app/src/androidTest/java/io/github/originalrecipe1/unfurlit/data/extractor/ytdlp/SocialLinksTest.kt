package io.github.originalrecipe1.unfurlit.data.extractor.ytdlp

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.linkcheck.SocialLinkCase
import io.github.originalrecipe1.unfurlit.linkcheck.SocialLinkObservation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Locale

/** Explicitly opted in: real sites can block CI or change their content at any time. */
@RunWith(Parameterized::class)
class SocialLinksTest(
    private val id: String,
    private val case: SocialLinkCase,
) {
    // Longer than yt-dlp's and gallery-dl's timeouts combined, so a slow site reports Timeout.
    @Test(timeout = 210_000)
    fun extractsExpectedMediaOrReportsExpectedFailure() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("liveLinks") == "true")
        val selectedIds = arguments.getString("linkId")
        assumeTrue(selectedIds == null || id in selectedIds.split(',').map(String::trim))
        val extractor = YtDlpMediaExtractor(InstrumentationRegistry.getInstrumentation().targetContext)
        val startedAt = SystemClock.elapsedRealtime()
        // Keep only a summary: the extraction result contains temporary playback credentials.
        val observed = try {
            SocialLinkObservation.of(runBlocking { extractor.extract(case.url) })
        } catch (error: ExtractionException) {
            SocialLinkObservation(outcome = error.error.javaClass.simpleName)
        }
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1_000.0
        Log.i(TAG, String.format(Locale.ROOT, "%s: %s in %.1f s", id, observed, seconds))
        val problems = case.problemsWith(observed)
        // scripts/social_links_report.py reads this message format.
        assertTrue(
            "$id: expected [${case.expectation}] but observed [$observed] (${problems.joinToString("; ")})",
            problems.isEmpty(),
        )
    }

    companion object {
        private const val TAG = "SocialLinksTest"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun links(): List<Array<Any>> {
            val json = InstrumentationRegistry.getInstrumentation().context.assets
                .open("social-links.json").bufferedReader().use { it.readText() }
            return SocialLinkCase.parseAll(json).map { case -> arrayOf<Any>(case.id, case) }
        }
    }
}
