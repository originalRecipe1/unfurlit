package io.github.originalrecipe1.unfurlit.data.network

import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

internal object PublicPageLoader {
    const val MAX_PAGE_BYTES = 4 * 1024 * 1024

    suspend fun load(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = SafeHttpClient.preflight.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val error = when (it.code) {
                                404, 410 -> ExtractionError.MediaUnavailable
                                401 -> ExtractionError.AuthenticationRequired
                                429 -> ExtractionError.NetworkFailure
                                else -> if (it.isSuccessful) null else ExtractionError.ExtractionFailed
                            }
                            if (error != null) throw ExtractionException(error)
                            if (it.request.url.encodedPath == "/login" ||
                                it.request.url.encodedPath.startsWith("/accounts/login")) {
                                throw ExtractionException(ExtractionError.AuthenticationRequired)
                            }
                            val body = it.body ?: throw ExtractionException(ExtractionError.ExtractionFailed)
                            val source = body.source()
                            // Bound decompressed response bytes before allocating a String.
                            source.request(MAX_PAGE_BYTES.toLong() + 1)
                            if (source.buffer.size > MAX_PAGE_BYTES) {
                                throw ExtractionException(ExtractionError.ExtractionFailed)
                            }
                            source.readUtf8()
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
}
