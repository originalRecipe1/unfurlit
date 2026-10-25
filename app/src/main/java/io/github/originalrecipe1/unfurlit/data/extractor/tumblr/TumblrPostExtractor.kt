package io.github.originalrecipe1.unfurlit.data.extractor.tumblr

import io.github.originalrecipe1.unfurlit.data.network.PublicPageLoader
import io.github.originalrecipe1.unfurlit.data.network.UnsafeNetworkTargetException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import java.io.IOException

internal class TumblrPostExtractor(
    /** Runs the app's route for a YouTube or Vimeo link; null leaves embeds to the existing engines. */
    private val extractEmbed: (suspend (url: String) -> ExtractionResult)? = null,
    private val loadPage: suspend (Request) -> String = PublicPageLoader::load,
) {
    /** Null permits the normal preflight and engines to try the original request URL. */
    suspend fun extract(sourceUrl: String, pageUrl: String): ExtractionResult? {
        val request = Request.Builder().url(pageUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        val post = try {
            TumblrPostParser.parse(sourceUrl, pageUrl, loadPage(request))
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            allowFallback(error)
        } catch (error: ExtractionException) {
            allowFallback(error)
        } ?: return null
        if (post.embeds.isEmpty()) return post.result
        val extractEmbed = extractEmbed ?: return null
        // The legacy blog page fails in the engines, so an embed's own error is the useful one.
        val embedded = post.embeds.associate { it.position to extractEmbed(it.url) }
        val native = post.result.media.iterator()
        val media = (0 until post.result.media.size + post.embeds.size).flatMap { position ->
            embedded[position]?.media ?: listOf(native.next())
        }
        if (media.size > MAX_MEDIA) throw ExtractionException(ExtractionError.ExtractionFailed)
        val first = embedded.getValue(post.embeds.first().position)
        // History keeps the Tumblr link and post metadata; the video's fills any gaps.
        return post.result.copy(
            title = post.result.title ?: first.title,
            description = post.result.description ?: first.description,
            thumbnailUrl = post.result.thumbnailUrl ?: first.thumbnailUrl.takeIf { post.embeds.first().position == 0 },
            media = media,
        )
    }

    private suspend fun allowFallback(error: Exception): Nothing? {
        currentCoroutineContext().ensureActive()
        // Falling through to Python must never bypass a rejected DNS/redirect or media target.
        if (generateSequence<Throwable>(error) { it.cause }.any { it is UnsafeNetworkTargetException }) {
            throw error
        }
        return null
    }

    private companion object {
        const val MAX_MEDIA = 50
    }
}
