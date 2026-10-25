package io.github.originalrecipe1.unfurlit.ui.viewer

import coil3.network.HttpException
import coil3.network.NetworkResponse
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFallbackRuleTest {
    private val fallback = "https://i.pximg.net/img-master/img/56360615_p3_master1200.jpg"

    @Test fun httpAndDecodeFailuresTryTheFallbackOnce() {
        for (error in listOf(HttpException(NetworkResponse(code = 500)), IllegalStateException("not an image"))) {
            assertTrue(shouldTryFallback(fallback, usingFallback = false, error))
            assertFalse(shouldTryFallback(fallback, usingFallback = true, error))
            assertFalse(shouldTryFallback(null, usingFallback = false, error))
        }
    }

    @Test fun cancellationIsNotAFailure() {
        assertFalse(shouldTryFallback(fallback, usingFallback = false, CancellationException("left the page")))
    }
}
