package io.github.originalrecipe1.unfurlit.data.extractor.ytdlp

import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class ExtractionErrorMapperTest {
    @Test fun classifiesRepresentativeSiteFailures() {
        val cases = mapOf(
            "Unsupported URL: https://example.com" to ExtractionError.UnsupportedUrl,
            "No video could be found in this post" to ExtractionError.UnsupportedUrl,
            "No video formats found!" to ExtractionError.UnsupportedUrl,
            "HTTP Error 404: Not Found" to ExtractionError.MediaUnavailable,
            "HTTP Error 410: Gone" to ExtractionError.MediaUnavailable,
            "Video has been removed" to ExtractionError.MediaUnavailable,
            "Private video. Sign in" to ExtractionError.AuthenticationRequired,
            "Login required" to ExtractionError.AuthenticationRequired,
            "The web client only works when logged-in" to ExtractionError.AuthenticationRequired,
            "Blocked due to its TLS fingerprint. May compromise security/cookies" to ExtractionError.ExtractionFailed,
            "Connection timed out" to ExtractionError.NetworkFailure,
            "Unexpected extractor response" to ExtractionError.ExtractionFailed,
        )
        cases.forEach { (message, expected) ->
            assertEquals(message, expected, RuntimeException(message).toDomainError())
        }
    }

    @Test fun recognizesWrappedNetworkFailure() {
        assertEquals(ExtractionError.NetworkFailure,
            RuntimeException("Extraction failed", IOException()).toDomainError())
    }
}
