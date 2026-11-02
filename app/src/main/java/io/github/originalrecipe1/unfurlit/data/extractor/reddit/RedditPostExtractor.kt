package io.github.originalrecipe1.unfurlit.data.extractor.reddit

import io.github.originalrecipe1.unfurlit.data.network.PublicPageLoader
import io.github.originalrecipe1.unfurlit.data.network.SafeHttpClient
import io.github.originalrecipe1.unfurlit.data.network.UnsafeNetworkTargetException
import io.github.originalrecipe1.unfurlit.domain.model.BlockingPlatform
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.domain.model.PlaybackSource
import io.github.originalrecipe1.unfurlit.domain.model.StreamFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import java.io.IOException

internal class RedditPostExtractor(
    private val session: RedditSession = RedditSession.shared,
    private val extractManifest: suspend (String) -> ExtractionResult,
    private val extractExternal: suspend (String) -> ExtractionResult,
    private val loadPage: suspend (Request) -> String = {
        PublicPageLoader.load(it, SafeHttpClient.preflight, acceptForbidden = true)
    },
) {
    suspend fun extract(sourceUrl: String, pageUrl: String): ExtractionResult? {
        if (RedditPostParser.postId(pageUrl) == null) return null
        val post = try {
            var lease = session.get()
            var parsed: RedditPost? = null
            for (attempt in 0..1) {
                val request = RedditSession.pageRequest(pageUrl).newBuilder().apply {
                    lease.cookieHeader(pageUrl)?.let { header("Cookie", it) }
                }.build()
                val html = loadPage(request)
                parsed = RedditPostParser.parse(pageUrl, html)
                if (parsed != null || !RedditPostParser.isChallenge(pageUrl, html)) break
                if (attempt == 0) lease = session.rebuild(lease) else {
                    session.invalidate(lease)
                    throw ExtractionException(ExtractionError.NetworkBlocked(BlockingPlatform.Reddit),
                        IOException("Reddit blocked by network security (anonymous page challenge)"))
                }
            }
            parsed
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            return allowFallback(error)
        } catch (error: ExtractionException) {
            if (error.error is ExtractionError.NetworkBlocked) throw error
            return allowFallback(error)
        } ?: return null
        val result = when (val content = post.content) {
            is RedditContent.Video -> extractManifest(content.manifest)
            is RedditContent.External -> extractExternal(content.url)
            is RedditContent.Images -> ExtractionResult(sourceUrl, "Reddit", post.title, post.author, null,
                content.urls.firstOrNull(), content.urls.map { image ->
                    ExtractedMedia.Image(PlaybackSource(image, emptyMap(), StreamFormat.Progressive,
                        when (image.substringAfterLast('.').lowercase()) {
                            "png" -> "image/png"
                            "gif" -> "image/gif"
                            "webp" -> "image/webp"
                            else -> "image/jpeg"
                        }, null))
                })
        }
        if (result.media.size > RedditPostParser.MAX_MEDIA) throw ExtractionException(ExtractionError.ExtractionFailed)
        return result.copy(sourceUrl = sourceUrl, platform = "Reddit", title = post.title ?: result.title,
            author = post.author ?: result.author)
    }

    private suspend fun allowFallback(error: Exception): Nothing? {
        currentCoroutineContext().ensureActive()
        if (generateSequence<Throwable>(error) { it.cause }.any { it is UnsafeNetworkTargetException }) throw error
        return null
    }
}
