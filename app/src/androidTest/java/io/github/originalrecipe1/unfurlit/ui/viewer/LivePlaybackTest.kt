package io.github.originalrecipe1.unfurlit.ui.viewer

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.paging.PagingData
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originalrecipe1.unfurlit.R
import io.github.originalrecipe1.unfurlit.data.network.SafeHttpClient
import io.github.originalrecipe1.unfurlit.data.repository.RepositoryFactory
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.domain.model.HistoryEntry
import io.github.originalrecipe1.unfurlit.domain.repository.HistoryRepository
import io.github.originalrecipe1.unfurlit.linkcheck.SocialLinkCase
import io.github.originalrecipe1.unfurlit.playback.ActivePlayback
import io.github.originalrecipe1.unfurlit.ui.player.LocalPlaybackHttpClient
import io.github.originalrecipe1.unfurlit.ui.player.isHttpForbidden
import io.github.originalrecipe1.unfurlit.ui.theme.UnfurlitTheme
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Explicitly opted in (`-e livePlayback true`): opens real links in the viewer, plays them and
 * records every media request, so intermittent HTTP 403s can be counted and described.
 *
 * - `linkId`: comma-separated fixture IDs; defaults to the YouTube fixtures.
 * - `rounds`: how many times to cycle through them, round-robin.
 * - `waitForRecovery`: `true` keeps watching after a 403 for the app's own URL refresh.
 *   By default the first 403 ends the attempt, as the earlier viewer checks did.
 * - `fake403`: `audio` or `video` refuses every request to the first media URL of that type
 *   with a local 403, to check both modes and the recovery without waiting for a real one.
 *   Those requests are recorded with `"faked": true`.
 *
 * Outcomes: PASS (no 403), RECOVERED_403 (a 403, then playback), FAIL_403, FAIL_PLAYBACK,
 * FAIL_EXTRACTION and TIMEOUT. Only PASS passes the test, so a recovery never counts as a
 * plain pass. Each attempt appends one JSON line to `files/live-playback/attempts.jsonl`.
 * Records keep a media URL's format, client and range parameters but no host, address or
 * signature.
 */
@RunWith(Parameterized::class)
@androidx.annotation.OptIn(UnstableApi::class)
class LivePlaybackTest(
    private val name: String,
    private val round: Int,
    private val case: SocialLinkCase,
) {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val scope = CoroutineScope(Dispatchers.Main)
    private var viewModel: ViewerViewModel? = null

    @After fun tearDown() {
        scope.cancel()
        viewModel?.let { model -> compose.runOnIdle { model.cancel() } }
    }

    @Test(timeout = 300_000)
    fun playsOrRecordsWhyNot() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("livePlayback") == "true")
        val waitForRecovery = arguments.getString("waitForRecovery") == "true"
        val playMs = ((arguments.getString("playSeconds")?.toDouble() ?: 3.0) * 1_000).toLong()

        val recorder = MediaRequestRecorder(arguments.getString("fake403"))
        val client = SafeHttpClient.streaming.newBuilder()
            .addInterceptor(recorder::refuseFaked)
            .addNetworkInterceptor(recorder)
            .build()
        val model = ViewerViewModel(
            context.applicationContext as Application,
            SavedStateHandle(),
            RepositoryFactory.mediaRepository(context),
            NoHistory,
        ).also { viewModel = it }

        // Each extraction's media URLs and when it became ready, to date every request.
        val extractions = CopyOnWriteArrayList<Pair<Long, Set<String>>>()
        val seenExtractions = Collections.newSetFromMap(IdentityHashMap<ExtractionResult, Boolean>())
        var refreshStarted = false
        val players = IdentityHashMap<Player, PlayerWatch>()
        var current: PlayerWatch? = null
        scope.launch {
            model.state.collect { state ->
                if (state !is ViewerState.Ready) return@collect
                if (state.refreshingMediaIndex != null) refreshStarted = true
                if (seenExtractions.add(state.extraction)) {
                    extractions += SystemClock.elapsedRealtime() to state.extraction.mediaUrls()
                }
            }
        }
        scope.launch {
            ActivePlayback.player.collect { player ->
                if (player == null) return@collect
                current = players.getOrPut(player) { PlayerWatch(player) }
            }
        }

        compose.setContent {
            CompositionLocalProvider(LocalPlaybackHttpClient provides client) {
                UnfurlitTheme { ViewerRoute(model, onBack = model::cancel, onShowHistory = {}) }
            }
        }
        val started = SystemClock.elapsedRealtime()
        compose.runOnIdle { model.open(case.url) }

        val retryLabel = context.getString(R.string.action_extract_again)
        var outcome = "TIMEOUT"
        var detail = ""
        val deadline = started + 240_000
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
            val state = compose.runOnIdle { model.state.value }
            val watch = compose.runOnIdle { current }
            val saw403 = recorder.forbiddenCount() > 0 ||
                compose.runOnIdle { players.values.any { it.forbidden } }
            when {
                state is ViewerState.Failed -> {
                    outcome = if (saw403) "FAIL_403" else "FAIL_EXTRACTION"
                    detail = state.error.javaClass.simpleName
                }
                !waitForRecovery && saw403 -> outcome = "FAIL_403"
                watch != null && compose.runOnIdle { watch.played(playMs) } ->
                    outcome = if (saw403) "RECOVERED_403" else "PASS"
                compose.onAllNodesWithText(retryLabel).fetchSemanticsNodes().isNotEmpty() -> {
                    outcome = if (saw403) "FAIL_403" else "FAIL_PLAYBACK"
                    detail = compose.runOnIdle { players.values.mapNotNull { it.error }.joinToString() }
                }
                else -> continue
            }
            break
        }

        val playerErrors = compose.runOnIdle { players.values.mapNotNull { it.error } }
        val record = JSONObject()
            .put("id", case.id)
            .put("round", round)
            .put("mode", if (waitForRecovery) "waitForRecovery" else "strict")
            .put("outcome", outcome)
            .put("detail", detail)
            .put("seconds", (SystemClock.elapsedRealtime() - started) / 1_000.0)
            .put("extractions", extractions.size)
            .put("refreshStarted", refreshStarted)
            .put("playerErrors", JSONArray(playerErrors))
            .put("requests", recorder.describe(extractions))
        File(context.filesDir, "live-playback").apply { mkdirs() }
            .resolve("attempts.jsonl").appendText(record.toString() + "\n")
        Log.i(TAG, "${case.id} round $round: $outcome ${record.getDouble("seconds")} s; " +
            "403s=${recorder.forbiddenCount()} extractions=${extractions.size}")
        assertTrue("${case.id} round $round: $outcome $detail", outcome == "PASS")
    }

    /** Called on the main thread: Media3 players are single-threaded. */
    private class PlayerWatch(private val player: Player) {
        private val startPosition = player.currentPosition
        var firstFrame = false
        var forbidden = false
        var error: String? = null

        init {
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { firstFrame = true }
                override fun onPlayerError(error: PlaybackException) {
                    forbidden = forbidden || error.isHttpForbidden()
                    this@PlayerWatch.error = error.errorCodeName
                }
            })
        }

        fun played(playMs: Long) = player.isPlaying && firstFrame &&
            player.currentPosition - startPosition >= playMs
    }

    /** Records media requests by format and range; never the host, address or signature. */
    private class MediaRequestRecorder(private val fakeForbiddenRole: String?) : Interceptor {
        private class Entry(val url: String, val atMs: Long, val wallSeconds: Long, val range: String?) {
            @Volatile var status = 0
            @Volatile var faked = false
            @Volatile var failure: String? = null
            val bytesRead = AtomicLong()
        }

        private val entries = CopyOnWriteArrayList<Entry>()

        private val refusedUrl = AtomicReference<String>()

        fun forbiddenCount() = entries.count { it.status == 403 }

        /** An application interceptor: network interceptors cannot answer by themselves. */
        fun refuseFaked(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()
            val role = request.url.queryParameter("mime")?.substringBefore('/')
            if (fakeForbiddenRole != null && role == fakeForbiddenRole) refusedUrl.compareAndSet(null, url)
            if (url != refusedUrl.get()) return chain.proceed(request)
            record(request).apply { status = 403; faked = true }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(403).message("Forbidden").body(ByteArray(0).toResponseBody()).build()
        }

        private fun record(request: Request) = Entry(
            request.url.toString(),
            SystemClock.elapsedRealtime(),
            System.currentTimeMillis() / 1_000,
            request.header("Range"),
        ).also { entries += it }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val entry = record(request)
            val response = try {
                chain.proceed(request)
            } catch (error: IOException) {
                entry.failure = error.javaClass.simpleName
                throw error
            }
            entry.status = response.code
            val body = response.body ?: return response
            val counted = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long =
                    super.read(sink, byteCount).also { if (it > 0) entry.bytesRead.addAndGet(it) }
            }
            return response.newBuilder()
                .body(counted.buffer().asResponseBody(body.contentType(), body.contentLength()))
                .build()
        }

        fun describe(extractions: List<Pair<Long, Set<String>>>): JSONArray {
            val result = JSONArray()
            entries.forEachIndexed { index, entry ->
                val uri = entry.url.toUri()
                val query = { key: String -> uri.getQueryParameter(key) }
                val readyAt = extractions.lastOrNull { entry.url in it.second }?.first
                    ?: extractions.lastOrNull { it.first <= entry.atMs }?.first
                val earlier = entries.subList(0, index).filter { it.url == entry.url }
                result.put(
                    JSONObject()
                        .put("role", query("mime")?.substringBefore('/') ?: "unknown")
                        .put("itag", query("itag"))
                        .put("client", query("c"))
                        .put("mime", query("mime"))
                        .put("clen", query("clen"))
                        .put("rangeHeader", entry.range)
                        .put("rangeParam", query("range"))
                        .put("status", entry.status)
                        .put("faked", entry.faked)
                        .put("failure", entry.failure)
                        .put("bytesRead", entry.bytesRead.get())
                        .put("bytesReadEarlierFromThisUrl", earlier.sumOf { it.bytesRead.get() })
                        .put("requestsEarlierToThisUrl", earlier.size)
                        .put("sinceExtractionMs", readyAt?.let { entry.atMs - it })
                        .put("expiresInSeconds", query("expire")?.toLongOrNull()?.minus(entry.wallSeconds))
                        // Presence and parameter names only; their values identify the session.
                        .put("hasPot", query("pot") != null)
                        .put("hasN", query("n") != null)
                        .put("ipbits", query("ipbits"))
                        .put("signedParams", query("sparams"))
                        .put("hostSuffix", uri.host?.split('.')?.takeLast(2)?.joinToString(".")),
                )
            }
            return result
        }
    }

    private object NoHistory : HistoryRepository {
        override fun observeHistory() = flowOf(PagingData.empty<HistoryEntry>())
        override suspend fun loadThumbnail(id: Long): ByteArray? = null
        override suspend fun recordView(result: ExtractionResult) = Unit
        override suspend fun remove(id: Long) = Unit
        override suspend fun clear() = Unit
    }

    companion object {
        private const val TAG = "LivePlaybackTest"

        private fun ExtractionResult.mediaUrls() = media.flatMap { media ->
            when (media) {
                is ExtractedMedia.Video -> listOfNotNull(media.videoSource.url, media.audioSource?.url)
                is ExtractedMedia.Audio -> listOf(media.source.url)
                is ExtractedMedia.Image -> listOf(media.source.url)
            }
        }.toSet()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun attempts(): List<Array<Any>> {
            val arguments = InstrumentationRegistry.getArguments()
            val json = InstrumentationRegistry.getInstrumentation().context.assets
                .open("social-links.json").bufferedReader().use { it.readText() }
            val cases = SocialLinkCase.parseAll(json)
            val ids = arguments.getString("linkId")?.split(',')?.map(String::trim)
            val selected = if (ids == null) {
                cases.filter { it.id.startsWith("youtube-") }
            } else {
                ids.map { id -> cases.single { it.id == id } }
            }
            val rounds = arguments.getString("rounds")?.toInt() ?: 1
            return (1..rounds).flatMap { round ->
                selected.map { case -> arrayOf<Any>("${case.id}#$round", round, case) }
            }
        }
    }
}
