package io.github.originalrecipe1.unfurlit.data.extractor.ytdlp

import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryDlFallbackTest {
    @Test
    fun noVideoFailedExtractionOrSignInFallsBackToGalleryDl() {
        assertTrue(ExtractionError.UnsupportedUrl.allowsGalleryDlFallback())
        assertTrue(ExtractionError.ExtractionFailed.allowsGalleryDlFallback())
        assertTrue(ExtractionError.AuthenticationRequired.allowsGalleryDlFallback())
        // gallery-dl may still reach Reddit through its API or OAuth retry.
        assertTrue(ExtractionError.NetworkBlocked("Reddit").allowsGalleryDlFallback())
        assertFalse(ExtractionError.MediaUnavailable.allowsGalleryDlFallback())
        assertFalse(ExtractionError.NetworkFailure.allowsGalleryDlFallback())
        assertFalse(ExtractionError.Timeout.allowsGalleryDlFallback())
    }

    @Test
    fun galleryDlFailureWinsOnlyWhenMoreSpecific() {
        val ytDlp = ExtractionException(ExtractionError.UnsupportedUrl)
        for (specific in listOf(
            ExtractionError.MediaUnavailable, ExtractionError.AuthenticationRequired, ExtractionError.NetworkBlocked("Reddit"),
        )) {
            val galleryDl = ExtractionException(specific)
            assertSame(galleryDl, preferredFailure(ytDlp, galleryDl))
        }
        for (generic in listOf(ExtractionError.UnsupportedUrl, ExtractionError.ExtractionFailed, ExtractionError.NetworkFailure)) {
            assertEquals(ExtractionError.UnsupportedUrl, preferredFailure(ytDlp, ExtractionException(generic)).error)
        }
        val blocked = ExtractionException(ExtractionError.NetworkBlocked("Reddit"))
        assertSame(blocked, preferredFailure(blocked, ExtractionException(ExtractionError.ExtractionFailed)))
    }
}
