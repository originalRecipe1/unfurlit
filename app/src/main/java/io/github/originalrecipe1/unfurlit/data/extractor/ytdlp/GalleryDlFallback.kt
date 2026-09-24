package io.github.originalrecipe1.unfurlit.data.extractor.ytdlp

import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException

/** yt-dlp failures after which the post may still have photos for gallery-dl. */
internal fun ExtractionError.allowsGalleryDlFallback(): Boolean =
    this == ExtractionError.UnsupportedUrl || this == ExtractionError.ExtractionFailed

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
