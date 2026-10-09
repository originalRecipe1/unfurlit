package io.github.originalrecipe1.unfurlit.data.extractor.tumblr

import io.github.originalrecipe1.unfurlit.data.network.PublicPageLoader
import io.github.originalrecipe1.unfurlit.data.network.UnsafeNetworkTargetException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import java.io.IOException

internal class TumblrPostExtractor(
    private val loadPage: suspend (Request) -> String = PublicPageLoader::load,
) {
    /** Null permits the normal preflight and engines to try the original request URL. */
    suspend fun extract(sourceUrl: String, pageUrl: String): ExtractionResult? {
        val request = Request.Builder().url(pageUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        return try {
            TumblrPostParser.parse(sourceUrl, pageUrl, loadPage(request))
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            allowFallback(error)
        } catch (error: ExtractionException) {
            allowFallback(error)
        }
    }

    private suspend fun allowFallback(error: Exception): ExtractionResult? {
        currentCoroutineContext().ensureActive()
        // Falling through to Python must never bypass a rejected DNS/redirect or media target.
        if (generateSequence<Throwable>(error) { it.cause }.any { it is UnsafeNetworkTargetException }) {
            throw error
        }
        return null
    }
}
