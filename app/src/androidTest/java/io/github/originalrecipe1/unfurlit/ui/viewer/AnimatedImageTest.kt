package io.github.originalrecipe1.unfurlit.ui.viewer

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.util.Base64
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import coil3.DrawableImage
import coil3.ComponentRegistry
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.gif.GifDecoder
import coil3.map.Mapper
import coil3.request.ImageRequest
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import io.github.originalrecipe1.unfurlit.data.extractor.ytdlp.YtDlpJsonParser
import io.github.originalrecipe1.unfurlit.data.history.HistoryThumbnailLoader
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.domain.model.PlaybackSource
import io.github.originalrecipe1.unfurlit.domain.model.StreamFormat
import io.github.originalrecipe1.unfurlit.ui.theme.UnfurlitTheme
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(DelicateCoilApi::class)
class AnimatedImageTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var originalLoader: ImageLoader
    private lateinit var loader: ImageLoader
    private lateinit var fixtureComponents: ComponentRegistry
    private val results = ConcurrentHashMap<String, SuccessResult>()
    private val errors = ConcurrentHashMap<String, Throwable>()
    private val visible = mutableStateOf(true)
    private val source = PlaybackSource(GIF_URL, emptyMap(), StreamFormat.Progressive, "image/gif", null)

    @Before fun setUp() {
        originalLoader = SingletonImageLoader.get(context)
        fixtureComponents = ComponentRegistry.Builder().apply {
            // Keep the application's real decoders; replace only the remote fixture bytes.
            originalLoader.components.decoderFactories.forEach { add(it) }
            add(Mapper<String, ByteBuffer> { data, _ ->
                ByteBuffer.wrap(when (data) {
                    GIF_URL -> gif
                    STILL_URL -> stillImage()
                    else -> error("Unexpected image request: $data")
                })
            })
        }.build()
        loader = originalLoader.newBuilder()
            .memoryCache(null)
            .diskCache(null)
            .components(fixtureComponents)
            .eventListener(object : EventListener() {
                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    results[request.data.toString()] = result
                }
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    errors[request.data.toString()] = result.throwable
                }
            })
            .build()
        SingletonImageLoader.setUnsafe(loader)
    }

    @After fun tearDown() {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        SingletonImageLoader.setUnsafe(originalLoader)
        loader.shutdown()
    }

    @Test fun directGifAnimatesOnlyOnTheActivePageInTheForeground() {
        val active = mutableStateOf(true)
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible.value) UnfurlitTheme {
                    ZoomableImage(source, "Animated GIF", active.value, {}, {}, Modifier.size(200.dp).testTag("gif"))
                }
            }
        }
        val animation = awaitAnimation()
        assertAnimatingPixels()
        compose.runOnIdle { active.value = false }
        compose.runOnIdle { assertFalse("Inactive page must stop", animation.isRunning) }
        compose.runOnIdle { active.value = true }
        compose.runOnIdle { assertTrue("Active page must restart", animation.isRunning) }
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { assertFalse("Background viewer must stop", animation.isRunning) }
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertTrue("Foreground viewer must restart", animation.isRunning) }
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { assertFalse("Disposed viewer must stop", animation.isRunning) }
    }

    @Test fun imgurGifFallbackAnimatesWithoutChangingItsImageClassification() {
        val result = YtDlpJsonParser.parse("https://imgur.com/example", """
            {"extractor_key":"Imgur","title":"GIF fallback","url":"$GIF_URL",
             "format_id":"0","ext":"gif","vcodec":"none","acodec":"none"}
        """.trimIndent())
        val image = result.media.single() as ExtractedMedia.Image
        assertEquals("0", image.source.formatId)
        compose.setContent {
            if (visible.value) UnfurlitTheme {
                MediaViewer(result, {}, {}, Modifier.testTag("gif"))
            }
        }
        awaitAnimation()
        assertAnimatingPixels()
    }

    @Test fun legacyGifDecoderAlsoRendersChangingFrames() = runBlocking {
        // Exercise the API 24-27 decoder on CI's API 30 device too. Draw manually:
        // MovieDrawable invalidates every frame, so a looping GIF never becomes
        // idle for Compose's screenshot synchronization.
        val result = loader.execute(ImageRequest.Builder(context).data(GIF_URL)
            .decoderFactory(GifDecoder.Factory()).size(40, 40).build()) as SuccessResult
        val drawable = (result.image as DrawableImage).drawable
        val animation = drawable as Animatable
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val colors = mutableSetOf<Int>()
        try {
            compose.runOnUiThread {
                drawable.setBounds(0, 0, 40, 40)
                animation.start()
            }
            withTimeout(5_000) {
                while (colors.size < 2) {
                    compose.runOnUiThread {
                        drawable.draw(canvas)
                        colors += bitmap.getPixel(20, 20)
                    }
                    delay(50)
                }
            }
            assertEquals(setOf(android.graphics.Color.RED, android.graphics.Color.BLUE), colors)
        } finally {
            compose.runOnUiThread { animation.stop() }
            bitmap.recycle()
        }
        assertFalse(animation.isRunning)
    }

    @Test fun inactivePageDoesNotStartWhenItsGifFinishesLoading() {
        compose.setContent {
            if (visible.value) UnfurlitTheme {
                ZoomableImage(source, "Inactive GIF", false, {}, {}, Modifier.size(200.dp))
            }
        }
        val animation = awaitAnimation(running = false)
        compose.runOnIdle { assertFalse(animation.isRunning) }
    }

    @Test fun gallerySwipesStopAndRestartGifAndZoomKeepsItAnimating() {
        val still = source.copy(url = STILL_URL, mediaMimeType = "image/png")
        val gallery = ExtractionResult(GIF_URL, null, "Gallery", null, null, null,
            listOf(ExtractedMedia.Image(source), ExtractedMedia.Image(still)))
        compose.setContent {
            if (visible.value) UnfurlitTheme { MediaViewer(gallery, {}, {}, Modifier.testTag("gif")) }
        }
        val first = awaitAnimation()
        assertAnimatingPixels()
        compose.onNodeWithTag("gif").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("Item 2 of 2").assertIsDisplayed()
        compose.runOnIdle { assertFalse("GIF on the previous gallery page must stop", first.isRunning) }
        compose.waitUntil(5_000) { results.containsKey(STILL_URL) }
        compose.onNodeWithTag("gif").performTouchInput { swipeRight() }
        compose.onNodeWithContentDescription("Item 1 of 2").assertIsDisplayed()
        awaitAnimation()
        val beforeZoom = compose.onNodeWithTag("gif").captureToImage().toPixelMap()
        val letterbox = beforeZoom[beforeZoom.width / 2, 1]
        assertTrue("Square GIF initially fits inside the taller viewer", letterbox.red < 0.1f && letterbox.blue < 0.1f)
        // A vertical pinch avoids confusing the pager's horizontal swipe gesture.
        compose.onNodeWithTag("gif").performTouchInput {
            pinch(
                Offset(center.x, height * 0.4f), Offset(center.x, height * 0.1f),
                Offset(center.x, height * 0.6f), Offset(center.x, height * 0.9f),
            )
        }
        assertAnimatingPixels()
        val afterZoom = compose.onNodeWithTag("gif").captureToImage().toPixelMap()
        val zoomed = afterZoom[afterZoom.width / 2, 1]
        assertTrue("Zoomed GIF must fill the former letterbox", zoomed.red > 0.9f || zoomed.blue > 0.9f)
        compose.onNodeWithContentDescription("Item 1 of 2").assertIsDisplayed()
    }

    @Test fun historySavesAStillJpegOfTheFirstGifFrame() = runBlocking {
        val extraction = ExtractionResult(GIF_URL, null, "GIF", null, null, null, listOf(ExtractedMedia.Image(source)))
        val bytes = requireNotNull(HistoryThumbnailLoader(context).load(extraction))
        assertEquals(0xff, bytes[0].toInt() and 0xff)
        assertEquals(0xd8, bytes[1].toInt() and 0xff)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(bitmap)
        assertTrue(bitmap.width in 1..192 && bitmap.height in 1..192)
        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        assertTrue("Thumbnail must contain the red first frame", android.graphics.Color.red(pixel) > 240)
        assertTrue(android.graphics.Color.blue(pixel) < 15)
        bitmap.recycle()
    }

    private fun awaitAnimation(running: Boolean = true): Animatable {
        compose.waitUntil(5_000) {
            results.containsKey(GIF_URL) || errors.containsKey(GIF_URL)
        }
        errors[GIF_URL]?.let { throw AssertionError("GIF did not load", it) }
        val image = results.getValue(GIF_URL).image
        assertTrue("GIF must decode as an animated drawable, got ${image.javaClass.simpleName}", image is DrawableImage)
        val animation = (image as DrawableImage).drawable as Animatable
        compose.runOnIdle { assertEquals("GIF running state", running, animation.isRunning) }
        return animation
    }

    private fun assertAnimatingPixels() {
        // Observe rendered pixels, not just the decoder type or isRunning flag.
        // GIF frame time uses the platform clock, independently of Compose's test clock.
        val colors = mutableSetOf<String>()
        compose.waitUntil(5_000) {
            val pixels = compose.onNodeWithTag("gif").captureToImage().toPixelMap()
            val color = pixels[pixels.width / 2, pixels.height / 2]
            if (color.red > 0.9f && color.blue < 0.1f) colors += "red"
            if (color.blue > 0.9f && color.red < 0.1f) colors += "blue"
            colors.size == 2
        }
    }

    companion object {
        private const val GIF_URL = "https://images.example/animated.gif"
        private const val STILL_URL = "https://images.example/still.png"

        private fun stillImage(): ByteArray {
            val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.GREEN)
            return try {
                ByteArrayOutputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    it.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }

        // Authored 40x40 red/blue frames, 300 ms each, looping forever. No live network.
        private val gif = Base64.decode(
            "R0lGODlhKAAoAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAHgAAACwAAAAAKAAoAAAIQwABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFixgzatzIsaPHjyBDihxJsqTJkyhTqlzJsqXLlzBjypxJs6bNmzgJBgQAIfkEAR4AAQAsAAAAACgAKACBAAD/AAAAAAAAAAAACEMAAQgcSLCgwYMIEypcyLChw4cQI0qcSLGixYsYM2rcyLGjx48gQ4ocSbKkyZMoU6pcybKly5cwY8qcSbOmzZs4CQYEADs=",
            Base64.DEFAULT,
        )
    }
}
