package io.github.originalrecipe1.unfurlit.data.extractor.ytdlp

import io.github.originalrecipe1.unfurlit.data.extractor.twitter.TwitterPhotoSupplement
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope

/**
 * X posts start one gallery lookup alongside yt-dlp. Its failure must not cancel
 * a working video lookup, and the same result serves as the photo supplement or
 * the normal fallback. Other sites retain the sequential fallback.
 */
internal suspend fun extractWithGalleryDlFallback(
    extractionUrl: String,
    extractYtDlp: suspend () -> ExtractionResult,
    extractGalleryDl: suspend (String) -> ExtractionResult,
    onFailure: (Throwable) -> Unit,
    photoGraceMillis: Long = TwitterPhotoSupplement.PHOTO_GRACE_MILLIS,
): ExtractionResult = supervisorScope {
    val gallery = TwitterPhotoSupplement.postUrl(extractionUrl)?.let { postUrl ->
        async { extractGalleryDl(postUrl) }
    }
    try {
        val ytDlpFailure = try {
            val result = extractYtDlp()
            return@supervisorScope if (gallery == null) result else TwitterPhotoSupplement.appendPhotos(
                extractionUrl,
                result,
                extractGallery = { gallery.await() },
                onFailure = onFailure,
                graceMillis = photoGraceMillis,
            )
        } catch (error: ExtractionException) {
            error
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw ExtractionException(ExtractionError.Timeout, error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ExtractionException(error.toDomainError(), error)
        }
        if (!ytDlpFailure.error.allowsGalleryDlFallback()) throw ytDlpFailure
        try {
            // Await the existing X lookup even if it has already failed. Retrying
            // it here would add a second run and another full gallery timeout.
            gallery?.await() ?: extractGalleryDl(extractionUrl)
        } catch (error: ExtractionException) {
            onFailure(error)
            throw preferredFailure(ytDlpFailure, error)
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw ytDlpFailure
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onFailure(error)
            throw preferredFailure(ytDlpFailure, ExtractionException(error.toDomainError(), error))
        }
    } finally {
        // Also covers a non-video result, an ineligible fallback, grace expiry,
        // and whole-request cancellation. The runner destroys its child process.
        gallery?.cancel()
    }
}

/**
 * yt-dlp failures after which gallery-dl may still succeed: the post may have
 * photos only, or gallery-dl may reach the site through a different API (Reddit's
 * JSON pages often refuse yt-dlp without an account while its API still answers).
 */
internal fun ExtractionError.allowsGalleryDlFallback(): Boolean =
    this == ExtractionError.UnsupportedUrl ||
        this == ExtractionError.ExtractionFailed ||
        this == ExtractionError.AuthenticationRequired

/**
 * The failure to report when both engines fail: gallery-dl's only when it knows
 * more specifically that the post is gone or needs sign-in.
 */
internal fun preferredFailure(
    ytDlpFailure: ExtractionException,
    galleryDlFailure: ExtractionException,
): ExtractionException = when (galleryDlFailure.error) {
    ExtractionError.MediaUnavailable, ExtractionError.AuthenticationRequired -> galleryDlFailure
    else -> ytDlpFailure
}
