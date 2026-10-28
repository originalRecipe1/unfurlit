package io.github.originalrecipe1.unfurlit.ui.viewer

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.domain.model.PlaybackSource
import io.github.originalrecipe1.unfurlit.domain.model.StreamFormat
import io.github.originalrecipe1.unfurlit.ui.player.LocalPlaybackHttpClient
import io.github.originalrecipe1.unfurlit.ui.theme.UnfurlitTheme
import kotlin.math.abs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A gallery's video page must stay inside the 420dp gallery box at any window width. */
@androidx.annotation.OptIn(UnstableApi::class)
class GalleryVideoBoundsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // 144x256 H.264, generated with ffmpeg's testsrc2 pattern.
    private val portraitVideo by lazy {
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("portrait-9x16.mp4").use { it.readBytes() }
    }

    @Test
    fun defaultRatioVideoStaysInsideTheGalleryOnATablet() {
        val (gallery, video) = layOut(TABLET, VideoResponse.Missing)

        assertInside(gallery, video)
        // 16:9 at 800dp would be 450dp tall, so it fits the height and is pillarboxed.
        assertEquals(gallery.height, video.height, PX_TOLERANCE)
        assertRatio(16f / 9f, video)
    }

    @Test
    fun defaultRatioVideoStillFillsThePhoneWidth() {
        val (gallery, video) = layOut(PHONE, VideoResponse.Missing)

        assertInside(gallery, video)
        assertEquals(gallery.width, video.width, PX_TOLERANCE)
        assertRatio(16f / 9f, video)
    }

    @Test
    fun portraitVideoIsPillarboxedInsideTheGalleryOnATablet() {
        val (gallery, video) = layOut(TABLET, VideoResponse.Portrait)

        assertInside(gallery, video)
        assertEquals(gallery.height, video.height, PX_TOLERANCE)
        assertRatio(9f / 16f, video)
    }

    @Test
    fun portraitVideoIsPillarboxedInsideTheGalleryOnAPhone() {
        val (gallery, video) = layOut(PHONE, VideoResponse.Portrait)

        assertInside(gallery, video)
        assertEquals(gallery.height, video.height, PX_TOLERANCE)
        assertRatio(9f / 16f, video)
    }

    private fun layOut(size: DpSize, response: VideoResponse): Pair<ComposeRect, ComposeRect> {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            when (response) {
                VideoResponse.Missing -> builder.code(404).message("Not Found")
                    .body(ByteArray(0).toResponseBody())
                VideoResponse.Portrait -> builder.code(200).message("OK")
                    .body(portraitVideo.toResponseBody("video/mp4".toMediaType()))
            }.build()
        }.build()
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                CompositionLocalProvider(LocalPlaybackHttpClient provides client) {
                    UnfurlitTheme {
                        // Laid out as in ViewerScreen: a scrolling column below the gallery.
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            MediaViewer(
                                GALLERY,
                                onRetry = {},
                                onViewed = {},
                                modifier = Modifier.fillMaxWidth().testTag(GALLERY_TAG),
                            )
                        }
                    }
                }
            }
        }
        if (response == VideoResponse.Portrait) {
            // Wait for the player to report the clip's 9:16 size.
            compose.waitUntil(15_000) {
                compose.runOnUiThread(::visiblePlayerBounds).singleOrNull()?.let { it.height > it.width } == true
            }
        }
        compose.waitForIdle()
        val gallery = compose.onNodeWithTag(GALLERY_TAG).fetchSemanticsNode().boundsInWindow
        val galleryHeight = with(compose.onNodeWithTag(GALLERY_TAG).fetchSemanticsNode().layoutInfo.density) { 420.dp.toPx() }
        assertEquals("Gallery height", galleryHeight, gallery.height, PX_TOLERANCE)
        return gallery to playerBounds()
    }

    private fun playerBounds(): ComposeRect {
        val bounds = compose.runOnUiThread(::visiblePlayerBounds)
        assertEquals("One visible video page", 1, bounds.size)
        return bounds.single()
    }

    private fun visiblePlayerBounds(): List<ComposeRect> =
        compose.activity.window.decorView.findPlayers().filter { it.isShown }.map { player ->
            val location = IntArray(2).also(player::getLocationInWindow)
            ComposeRect(
                location[0].toFloat(),
                location[1].toFloat(),
                (location[0] + player.width).toFloat(),
                (location[1] + player.height).toFloat(),
            )
        }

    private fun assertInside(gallery: ComposeRect, video: ComposeRect) {
        assertTrue(
            "Video $video must stay inside gallery $gallery",
            video.left >= gallery.left - PX_TOLERANCE && video.top >= gallery.top - PX_TOLERANCE &&
                video.right <= gallery.right + PX_TOLERANCE && video.bottom <= gallery.bottom + PX_TOLERANCE,
        )
    }

    private fun assertRatio(expected: Float, video: ComposeRect) {
        assertTrue("Video ratio ${video.width / video.height} is not $expected", abs(video.width / video.height - expected) < 0.02f)
    }

    private fun View.findPlayers(): List<PlayerView> = when (this) {
        is PlayerView -> listOf(this)
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).findPlayers() }
        else -> emptyList()
    }

    private enum class VideoResponse { Missing, Portrait }

    private companion object {
        const val GALLERY_TAG = "gallery"
        const val PX_TOLERANCE = 1.5f
        val TABLET = DpSize(800.dp, 1280.dp)
        val PHONE = DpSize(411.dp, 900.dp)

        private fun source(url: String, mimeType: String) =
            PlaybackSource(url, emptyMap(), StreamFormat.Progressive, mimeType, null)

        val GALLERY = ExtractionResult(
            "https://example.tumblr.com/post/1", "Tumblr", "Gallery", null, null, null,
            listOf(
                ExtractedMedia.Video(source("https://media.example/video.mp4", "video/mp4"), null, 2),
                ExtractedMedia.Image(source("https://media.example/photo.png", "image/png")),
            ),
        )
    }
}
