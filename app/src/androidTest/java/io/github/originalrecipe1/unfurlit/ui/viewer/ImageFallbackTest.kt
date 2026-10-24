package io.github.originalrecipe1.unfurlit.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ComponentRegistry
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.annotation.DelicateCoilApi
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.network.HttpException
import coil3.network.NetworkResponse
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import io.github.originalrecipe1.unfurlit.R
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.domain.model.PlaybackSource
import io.github.originalrecipe1.unfurlit.domain.model.StreamFormat
import io.github.originalrecipe1.unfurlit.ui.theme.UnfurlitTheme
import java.io.ByteArrayOutputStream
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Pixiv's CDN can fail an original (artwork 56360615 page 4 returns HTTP 500) while its resized copy loads. */
@OptIn(DelicateCoilApi::class)
class ImageFallbackTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var originalLoader: ImageLoader
    private lateinit var loader: ImageLoader
    private val requests = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
    private val loaded = Collections.synchronizedList(mutableListOf<String>())
    private val fetchCancelled = CompletableDeferred<Unit>()
    private val visible = mutableStateOf(true)

    @Before fun setUp() {
        originalLoader = SingletonImageLoader.get(context)
        val fixtureComponents = ComponentRegistry.Builder().apply {
            // Keep the application's real decoders; replace only the remote responses.
            originalLoader.components.decoderFactories.forEach { add(it) }
            add(object : Fetcher.Factory<Uri> {
                override fun create(data: Uri, options: Options, imageLoader: ImageLoader) = object : Fetcher {
                    override suspend fun fetch(): FetchResult {
                        val url = data.toString()
                        requests += url to options.httpHeaders["Referer"]
                        return when (url) {
                            SERVER_ERROR, FAILING_FALLBACK -> throw HttpException(NetworkResponse(code = 500))
                            UNDECODABLE -> result("not an image".toByteArray(), "image/jpeg")
                            HANGING -> try { awaitCancellation() } finally { fetchCancelled.complete(Unit) }
                            MASTER, OTHER_PAGE -> result(png(), "image/png")
                            else -> error("Unexpected image request: $url")
                        }
                    }
                    private fun result(bytes: ByteArray, mimeType: String) = SourceFetchResult(
                        ImageSource(Buffer().write(bytes), options.fileSystem), mimeType, DataSource.NETWORK)
                }
            })
        }.build()
        loader = originalLoader.newBuilder()
            .memoryCache(null)
            .diskCache(null)
            .components(fixtureComponents)
            .eventListener(object : EventListener() {
                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    loaded += request.data.toString()
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

    @Test fun serverErrorLoadsTheFallbackOnceWithTheSameReferer() {
        var viewed = 0
        show(SERVER_ERROR, MASTER, onViewed = { viewed++ })
        compose.waitUntil(5_000) { MASTER in loaded }
        compose.runOnIdle {
            assertEquals(listOf(SERVER_ERROR to REFERER, MASTER to REFERER), requests.toList())
            assertEquals(1, viewed)
        }
        compose.onNodeWithText(context.getString(R.string.image_load_failed)).assertDoesNotExist()
    }

    @Test fun undecodableOriginalLoadsTheFallback() {
        show(UNDECODABLE, MASTER)
        compose.waitUntil(5_000) { MASTER in loaded }
        compose.runOnIdle { assertEquals(listOf(UNDECODABLE, MASTER), requests.map { it.first }) }
        compose.onNodeWithText(context.getString(R.string.image_load_failed)).assertDoesNotExist()
    }

    @Test fun failingFallbackShowsTheExistingErrorWithoutAnotherAttempt() {
        show(SERVER_ERROR, FAILING_FALLBACK)
        compose.onNodeWithText(context.getString(R.string.image_load_failed)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_extract_again)).assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(listOf(SERVER_ERROR to REFERER, FAILING_FALLBACK to REFERER), requests.toList())
    }

    @Test fun imageWithoutFallbackFailsAsBefore() {
        show(SERVER_ERROR, null)
        compose.onNodeWithText(context.getString(R.string.image_load_failed)).assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(listOf(SERVER_ERROR), requests.map { it.first })
    }

    @Test fun cancelledOriginalDoesNotRequestTheFallback() {
        show(HANGING, MASTER)
        compose.waitUntil(5_000) { requests.isNotEmpty() }
        compose.runOnIdle { visible.value = false }
        compose.waitUntil(5_000) { fetchCancelled.isCompleted }
        compose.waitForIdle()
        assertEquals(listOf(HANGING), requests.map { it.first })
    }

    @Test fun galleryPageUsesItsOwnFallback() {
        val gallery = ExtractionResult(PAGE, "Pixiv", "Gallery", null, null, OTHER_PAGE, listOf(
            ExtractedMedia.Image(source(OTHER_PAGE), null),
            ExtractedMedia.Image(source(SERVER_ERROR), MASTER),
        ))
        compose.setContent {
            if (visible.value) UnfurlitTheme { MediaViewer(gallery, {}, {}, Modifier.testTag("viewer")) }
        }
        compose.waitUntil(5_000) { OTHER_PAGE in loaded }
        compose.onNodeWithTag("viewer").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("Item 2 of 2").assertIsDisplayed()
        compose.waitUntil(5_000) { MASTER in loaded }
        compose.runOnIdle {
            assertEquals(listOf(SERVER_ERROR to REFERER, MASTER to REFERER),
                requests.filter { it.first != OTHER_PAGE })
        }
        compose.onNodeWithText(context.getString(R.string.image_load_failed)).assertDoesNotExist()
    }

    private fun show(original: String, fallback: String?, onViewed: () -> Unit = {}) {
        compose.setContent {
            if (visible.value) UnfurlitTheme {
                ZoomableImage(source(original), "Pixiv page", true, {}, onViewed, Modifier.size(200.dp), fallback)
            }
        }
    }

    companion object {
        private const val PAGE = "https://www.pixiv.net/artworks/56360615"
        private const val REFERER = "https://www.pixiv.net/"
        private const val ORIGINALS = "https://i.pximg.net/img-original/img/2016/04/15/11/53/44"
        private const val SERVER_ERROR = "$ORIGINALS/56360615_p3.jpg"
        private const val UNDECODABLE = "$ORIGINALS/56360615_p5.jpg"
        private const val HANGING = "$ORIGINALS/56360615_p6.jpg"
        private const val OTHER_PAGE = "$ORIGINALS/56360615_p0.jpg"
        private const val MASTER = "https://i.pximg.net/img-master/img/2016/04/15/11/53/44/56360615_p3_master1200.jpg"
        private const val FAILING_FALLBACK = "https://i.pximg.net/img-master/img/56360615_p3_master1200.jpg"

        private fun source(url: String) =
            PlaybackSource(url, mapOf("Referer" to REFERER), StreamFormat.Progressive, null, null)

        private fun png(): ByteArray {
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
    }
}
