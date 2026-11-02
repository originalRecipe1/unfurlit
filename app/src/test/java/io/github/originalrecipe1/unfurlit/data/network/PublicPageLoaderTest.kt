package io.github.originalrecipe1.unfurlit.data.network

import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Synthetic interceptor responses: these tests open no sockets. */
class PublicPageLoaderTest {
    private val request = Request.Builder().url("https://www.reddit.com/comments/test/").build()
    private fun client(body: String, status: Int) = OkHttpClient.Builder().addInterceptor {
        Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .body(body.toResponseBody()).build()
    }.build()

    @Test fun forbiddenHtmlIsReadableOnlyWhenExplicitlyEnabled() = runBlocking {
        val client = client("blocked by network security", 403)
        assertEquals("blocked by network security", PublicPageLoader.load(request, client, acceptForbidden = true))
        val error = assertThrows(ExtractionException::class.java) { runBlocking { PublicPageLoader.load(request, client) } }
        assertEquals(ExtractionError.ExtractionFailed, error.error)
    }

    @Test fun acceptingForbiddenNeverRelaxesOtherStatusesOrSizeLimit() {
        val oversized = "a".repeat(PublicPageLoader.MAX_PAGE_BYTES + 1)
        for ((body, status) in listOf(oversized to 403, "gone" to 404)) {
            assertThrows(ExtractionException::class.java) {
                runBlocking { PublicPageLoader.load(request, client(body, status), acceptForbidden = true) }
            }
        }
    }
}
