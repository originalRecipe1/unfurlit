package io.github.originalrecipe1.unfurlit.data.extractor.ytdlp

import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RepeatedFailureLogTest {
    @Test fun anEmbedFailureRethrownOrWrappedByItsPostIsARepeat() {
        val embed = ExtractionException(ExtractionError.MediaUnavailable)
        assertTrue(repeatsLoggedFailure(embed, embed))
        val raw = IOException("Connection reset")
        assertTrue(repeatsLoggedFailure(ExtractionException(ExtractionError.NetworkFailure, raw), raw))
    }

    @Test fun differentFailuresAreAlwaysLogged() {
        val first = ExtractionException(ExtractionError.AuthenticationRequired)
        assertFalse(repeatsLoggedFailure(first, null))
        // Vimeo's player-route failure and its page-route failure are both diagnostics.
        assertFalse(repeatsLoggedFailure(ExtractionException(ExtractionError.AuthenticationRequired), first))
        // A timeout raised after an earlier logged failure is not hidden by it.
        assertFalse(repeatsLoggedFailure(first, ExtractionException(ExtractionError.Timeout, first)))
    }
}
