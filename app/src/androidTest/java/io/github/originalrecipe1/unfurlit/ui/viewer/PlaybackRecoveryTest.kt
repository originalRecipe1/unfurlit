package io.github.originalrecipe1.unfurlit.ui.viewer

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.paging.PagingData
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originalrecipe1.unfurlit.R
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.domain.model.HistoryEntry
import io.github.originalrecipe1.unfurlit.domain.model.PlaybackSource
import io.github.originalrecipe1.unfurlit.domain.model.StreamFormat
import io.github.originalrecipe1.unfurlit.domain.repository.HistoryRepository
import io.github.originalrecipe1.unfurlit.domain.repository.MediaRepository
import io.github.originalrecipe1.unfurlit.playback.ActivePlayback
import io.github.originalrecipe1.unfurlit.ui.player.PlaybackResume
import io.github.originalrecipe1.unfurlit.ui.player.LocalPlaybackHttpClient
import io.github.originalrecipe1.unfurlit.ui.player.isHttpForbidden
import io.github.originalrecipe1.unfurlit.ui.theme.UnfurlitTheme
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackRecoveryTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sourceUrl = "https://www.youtube.com/watch?v=recovery-test"
    private lateinit var playbackClient: OkHttpClient
    private lateinit var result: ExtractionResult
    private lateinit var viewModel: ViewerViewModel
    private val requests = mutableListOf<String>()
    private val views = mutableListOf<ExtractionResult>()
    private lateinit var extract: suspend (String) -> ExtractionResult

    @Before fun setUp() {
        // Generated PCM exercises the real player and seeking without codecs or a network fixture.
        val size = 8_000 * 2 * 15
        val wav = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
        }.array()
        playbackClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(wav.toResponseBody("audio/wav".toMediaType())).build()
        }.build()
        result = ExtractionResult(
            sourceUrl, "YouTube", "Recovery test", null, null, null,
            listOf(ExtractedMedia.Video(
                PlaybackSource("https://media.example/video", emptyMap(), StreamFormat.Progressive, "audio/wav", "test"),
                null, 15,
            )),
        )
        extract = { result }
        val repository = object : MediaRepository {
            override suspend fun open(url: String): ExtractionResult {
                requests += url
                return extract(url)
            }
        }
        val history = object : HistoryRepository {
            override fun observeHistory() = flowOf(PagingData.empty<HistoryEntry>())
            override suspend fun loadThumbnail(id: Long): ByteArray? = null
            override suspend fun recordView(result: ExtractionResult) { views += result }
            override suspend fun remove(id: Long) = Unit
            override suspend fun clear() = Unit
        }
        compose.runOnIdle {
            viewModel = ViewerViewModel(context.applicationContext as Application, SavedStateHandle(), repository, history)
            viewModel.open(sourceUrl)
        }
    }

    @After fun tearDown() {
        if (::viewModel.isInitialized) compose.runOnIdle { viewModel.cancel() }
        compose.waitForIdle()
    }

    @Test fun player403RefreshesOnceAndResumesEvenWhenUrlsAreUnchanged() {
        showViewer()
        val original = awaitPlayer()
        compose.runOnIdle {
            original.pause()
            failPlayer(original, 403, 2_300)
        }
        val replacement = awaitPlayer { it !== original }
        compose.runOnIdle {
            assertEquals(listOf(sourceUrl, sourceUrl), requests)
            assertEquals(2_300L, replacement.currentPosition)
            assertFalse(replacement.playWhenReady)
            assertEquals(PlaybackResume(0, 2_300, false), ready().playbackResume)
            replacement.play()
        }
        compose.waitUntil(10_000) {
            compose.runOnIdle { replacement.isPlaying && replacement.currentPosition > 2_500 }
        }
        compose.runOnIdle { failPlayer(replacement, 403, replacement.currentPosition) }
        compose.waitUntil(10_000) { compose.runOnIdle { replacement.playerError != null } }
        compose.onNodeWithText(context.getString(R.string.action_extract_again)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, requests.size) }
    }

    @Test fun playingVideoAutomaticallyContinuesAfter403() {
        showViewer()
        val original = awaitPlayer()
        compose.runOnIdle {
            original.play()
            failPlayer(original, 403, 1_500)
        }
        val replacement = awaitPlayer { it !== original }
        compose.waitUntil(10_000) {
            compose.runOnIdle { replacement.isPlaying && replacement.currentPosition > 1_700 }
        }
        compose.runOnIdle {
            assertEquals(PlaybackResume(0, 1_500, true), ready().playbackResume)
            assertEquals(2, requests.size)
        }
    }

    @Test fun otherPlayerHttpErrorsDoNotReextract() {
        showViewer()
        val player = awaitPlayer()
        compose.runOnIdle { failPlayer(player, 404, 0) }
        compose.waitUntil(10_000) { compose.runOnIdle { player.playerError != null } }
        compose.onNodeWithText(context.getString(R.string.action_extract_again)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(sourceUrl), requests) }
    }

    @Test fun onlyAnActual403InTheCauseChainIsEligible() {
        for (status in listOf(401, 403, 404, 429, 500)) {
            val error = PlaybackException("Source error", IOException(httpError(status)), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
            assertEquals(status == 403, error.isHttpForbidden())
        }
        assertFalse(IOException("Response code: 403").isHttpForbidden())
    }

    @Test fun refreshFailureStopsAndManualRetryStartsANewAttempt() {
        compose.runOnIdle {
            extract = { throw ExtractionException(ExtractionError.NetworkFailure) }
            assertTrue(viewModel.refreshPlayback(result, PlaybackResume(0, 0, true)))
            assertEquals(ViewerState.Failed(sourceUrl, ExtractionError.NetworkFailure), viewModel.state.value)
            assertFalse(viewModel.refreshPlayback(result, PlaybackResume(0, 0, true)))
            assertEquals(2, requests.size)
            extract = { result }
            viewModel.retry()
            assertTrue(viewModel.refreshPlayback(result, PlaybackResume(0, 0, true)))
            assertEquals(4, requests.size)
        }
    }

    @Test fun pendingRefreshCannotRestartOrReplaceANewlyOpenedLink() {
        val pending = CompletableDeferred<ExtractionResult>()
        val another = result.copy(sourceUrl = "https://www.youtube.com/watch?v=another")
        compose.runOnIdle {
            extract = { withContext(NonCancellable) { pending.await() } }
            assertTrue(viewModel.refreshPlayback(result, PlaybackResume(0, 0, true)))
            assertFalse(viewModel.refreshPlayback(result, PlaybackResume(0, 0, true)))
            assertEquals(2, requests.size)
            extract = { another }
            viewModel.open(another.sourceUrl)
            pending.complete(result)
        }
        compose.runOnIdle {
            assertSame(another, ready().extraction)
            assertFalse(viewModel.refreshPlayback(result, PlaybackResume(0, 0, true)))
            assertTrue(viewModel.refreshPlayback(another, PlaybackResume(0, 0, true)))
        }
    }

    @Test fun closingViewerDiscardsPendingRefresh() {
        val pending = CompletableDeferred<ExtractionResult>()
        compose.runOnIdle {
            extract = { withContext(NonCancellable) { pending.await() } }
            assertTrue(viewModel.refreshPlayback(result, PlaybackResume(0, 0, true)))
            viewModel.cancel()
            pending.complete(result)
        }
        compose.runOnIdle {
            assertEquals(ViewerState.Idle, viewModel.state.value)
            assertEquals(2, requests.size)
        }
    }

    @Test fun galleryPositionIsRetainedWithoutRecordingAnotherView() {
        val gallery = result.copy(media = result.media + result.media)
        val refreshed = gallery.copy(title = "Fresh metadata")
        compose.runOnIdle {
            extract = { gallery }
            viewModel.open(sourceUrl)
            viewModel.recordView(gallery)
            extract = { refreshed }
            assertTrue(viewModel.refreshPlayback(gallery, PlaybackResume(1, 9_000, true)))
            assertEquals(PlaybackResume(1, 9_000, true), ready().playbackResume)
            viewModel.recordView(refreshed)
            assertEquals(listOf(gallery), views)
            assertFalse(viewModel.refreshPlayback(refreshed, PlaybackResume(1, 9_000, true)))
        }
    }

    private fun showViewer() {
        compose.setContent {
            CompositionLocalProvider(LocalPlaybackHttpClient provides playbackClient) {
                UnfurlitTheme { ViewerRoute(viewModel, onBack = viewModel::cancel, onShowHistory = {}) }
            }
        }
    }

    private fun ready() = viewModel.state.value as ViewerState.Ready

    private fun awaitPlayer(predicate: (ExoPlayer) -> Boolean = { true }): ExoPlayer {
        var ready: ExoPlayer? = null
        compose.waitUntil(15_000) {
            compose.runOnIdle {
                ready = (ActivePlayback.player.value as? ExoPlayer)
                    ?.takeIf { predicate(it) && it.playbackState == Player.STATE_READY }
                ready != null
            }
        }
        return requireNotNull(ready)
    }

    private fun httpError(status: Int) = HttpDataSource.InvalidResponseCodeException(
        status, null, null, emptyMap(), DataSpec("https://media.example/expired.mp4".toUri()), byteArrayOf(),
    )

    private fun failPlayer(player: ExoPlayer, status: Int, position: Long) {
        val factory = DataSource.Factory {
            object : DataSource {
                override fun addTransferListener(transferListener: TransferListener) = Unit
                override fun open(dataSpec: DataSpec): Long = throw httpError(status)
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
                override fun getUri() = "https://media.example/expired.mp4".toUri()
                override fun close() = Unit
            }
        }
        player.setMediaSource(
            ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri("https://media.example/expired.mp4")),
            position,
        )
        player.prepare()
    }
}
