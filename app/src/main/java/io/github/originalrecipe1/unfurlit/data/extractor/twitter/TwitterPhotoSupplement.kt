package io.github.originalrecipe1.unfurlit.data.extractor.twitter

import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.util.UrlValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.URI

/** yt-dlp's Twitter extractor omits photos even when a post also has a video. */
internal object TwitterPhotoSupplement {
    suspend fun appendPhotos(
        extractionUrl: String,
        result: ExtractionResult,
        extractGallery: suspend (String) -> ExtractionResult,
        onFailure: (Exception) -> Unit,
    ): ExtractionResult {
        if (result.media.none { it is ExtractedMedia.Video } || result.media.size >= MAX_MEDIA_ENTRIES) {
            return result
        }
        val postUrl = postUrl(extractionUrl) ?: return result
        val gallery = try {
            extractGallery(postUrl)
        } catch (error: TimeoutCancellationException) {
            // An optional lookup may time out, but cancellation of the whole request
            // (including an enclosing timeout) must still stop extraction.
            currentCoroutineContext().ensureActive()
            onFailure(error)
            return result
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onFailure(error)
            return result
        }

        val seen = result.media.filterIsInstance<ExtractedMedia.Image>()
            .mapTo(mutableSetOf()) { it.source.url }
        // Keep yt-dlp's video formats, audio, headers and metadata. gallery-dl may
        // return the same videos at different resolutions; append only new photos.
        val photos = gallery.media.filterIsInstance<ExtractedMedia.Image>()
            .filter { seen.add(it.source.url) }
            .take(MAX_MEDIA_ENTRIES - result.media.size)
        return if (photos.isEmpty()) result else result.copy(media = result.media + photos)
    }

    private fun postUrl(url: String): String? {
        if (!UrlValidator.isAllowed(url)) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.rawUserInfo != null || uri.host?.lowercase()?.trimEnd('.') !in HOSTS) return null
        val defaultPort = if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
        if (uri.port != -1 && uri.port != defaultPort) return null
        val match = POST_PATH.matchEntire(uri.rawPath.orEmpty()) ?: return null
        return "https://x.com/i/web/status/${match.groupValues[1]}"
    }

    private const val MAX_MEDIA_ENTRIES = 50
    private val HOSTS = setOf("x.com", "www.x.com", "mobile.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com", "m.twitter.com")
    private val POST_PATH = Regex("/(?:[A-Za-z0-9_]+/status|i/web/status|statuses)/([0-9]+)(?:/(?:photo|video)/[0-9]+)?/?")
}
