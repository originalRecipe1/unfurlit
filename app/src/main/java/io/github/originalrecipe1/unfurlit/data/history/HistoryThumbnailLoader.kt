package io.github.originalrecipe1.unfurlit.data.history

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import coil3.SingletonImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.util.UrlValidator
import java.io.ByteArrayOutputStream

/** Keeps only a small, re-encoded image, never its CDN URL or request credentials. */
internal class HistoryThumbnailLoader(private val context: Context) {
    suspend fun load(result: ExtractionResult): ByteArray? = withTimeoutOrNull(5_000) {
        val image = result.media.firstOrNull() as? ExtractedMedia.Image
        val url = result.thumbnailUrl
            ?: (result.media.firstOrNull() as? ExtractedMedia.Audio)?.artworkUrl
            ?: image?.source?.url
            ?: return@withTimeoutOrNull null
        if (!UrlValidator.isAllowedHttps(url)) return@withTimeoutOrNull null
        try {
            val request = ImageRequest.Builder(context)
                .data(url)
                // Save only the first frame of animated images as the existing still JPEG.
                .decoderFactory(BitmapFactoryDecoder.Factory())
                .size(192, 192)
                .allowHardware(false)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .apply {
                    if (url == image?.source?.url) {
                        httpHeaders(NetworkHeaders.Builder().apply {
                            image.source.headers.forEach { (name, value) -> set(name, value) }
                        }.build())
                    }
                }
                .build()
            val loaded = SingletonImageLoader.get(context).execute(request) as? SuccessResult
                ?: return@withTimeoutOrNull null
            val bitmap = loaded.image.toBitmap()
            val ratio = minOf(1f, 192f / maxOf(bitmap.width, bitmap.height))
            val scaled = bitmap.scale(
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
            try {
                ByteArrayOutputStream().use { output ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 80, output)
                    output.toByteArray().takeIf { it.size <= 48 * 1024 }
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null // History remains usable without artwork, including when offline.
        }
    }
}
