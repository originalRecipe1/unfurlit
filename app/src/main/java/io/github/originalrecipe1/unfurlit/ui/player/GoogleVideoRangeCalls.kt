package io.github.originalrecipe1.unfurlit.ui.player

import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer

/**
 * googlevideo sends an open-ended request (`Range: bytes=N-`, or none) at about twice the
 * stream's bitrate, which stalls playback, but a bounded range at full speed. Like yt-dlp's
 * `http_chunk_size`, this asks for such a stream in [chunkBytes] ranges and gives the player
 * one response for all of them, with the total length from `Content-Range`. Every range is
 * a call of [client]. Other hosts and bounded requests are passed on unchanged.
 */
internal class GoogleVideoRangeCalls(
    private val client: Call.Factory,
    private val chunkBytes: Long = CHUNK_BYTES,
) : Call.Factory {
    override fun newCall(request: Request): Call {
        val start = openEndedStart(request) ?: return client.newCall(request)
        return RangedCall(request, start)
    }

    private fun rangeRequest(request: Request, from: Long, total: Long?): Request {
        val end = (from + chunkBytes).let { if (total != null) minOf(it, total) else it } - 1
        return request.newBuilder().header("Range", "bytes=$from-$end").build()
    }

    private inner class RangedCall(private val request: Request, private val start: Long) : Call {
        private val first = client.newCall(rangeRequest(request, start, null))
        private val executed = AtomicBoolean()
        @Volatile private var canceled = false
        @Volatile private var current: Call = first

        override fun request() = request

        override fun execute(): Response {
            check(executed.compareAndSet(false, true)) { "Already executed" }
            return joined(first.execute())
        }

        override fun enqueue(responseCallback: Callback) {
            check(executed.compareAndSet(false, true)) { "Already executed" }
            first.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = responseCallback.onFailure(this@RangedCall, e)

                override fun onResponse(call: Call, response: Response) {
                    val joined = try {
                        joined(response)
                    } catch (error: IOException) {
                        response.close()
                        return responseCallback.onFailure(this@RangedCall, error)
                    }
                    responseCallback.onResponse(this@RangedCall, joined)
                }
            })
        }

        override fun cancel() {
            canceled = true
            first.cancel()
            current.cancel()
        }

        override fun isExecuted() = executed.get()

        override fun isCanceled() = canceled

        override fun timeout(): Timeout = first.timeout()

        override fun clone(): Call = RangedCall(request, start)

        /**
         * A 200 means the server ignored the range and sends the whole file: the player
         * streams it as before. Errors, a 416 at the end included, also reach it unchanged.
         */
        private fun joined(response: Response): Response {
            if (response.code != 206) return response
            val range = ContentRange.parse(response.header("Content-Range"))
            if (range == null || range.first != start) {
                throw ProtocolException("Unexpected Content-Range for bytes from $start")
            }
            val total = range.total
            val body = Ranges(response, range).buffer()
                .asResponseBody(response.body?.contentType(), total?.minus(start) ?: -1)
            return response.newBuilder()
                .request(request)
                .apply {
                    if (total == null) {
                        removeHeader("Content-Range")
                        removeHeader("Content-Length")
                    } else {
                        header("Content-Range", "bytes $start-${total - 1}/$total")
                        header("Content-Length", (total - start).toString())
                    }
                }
                .body(body)
                .build()
        }

        /** Reads one range after another; a later range's refusal fails the read. */
        private inner class Ranges(private var response: Response, range: ContentRange) : Source {
            private val total = range.total
            private var position = range.first
            private var requestedEnd = start + chunkBytes
            private var lastRange = false
            private var rangeStart = position

            override fun read(sink: Buffer, byteCount: Long): Long {
                while (true) {
                    val read = response.body!!.source().read(sink, byteCount)
                    if (read != -1L) return read.also { position += it }
                    response.close()
                    if (position == rangeStart) throw ProtocolException("Empty range at byte $position")
                    // Without a total, a range shorter than asked for was the last one.
                    if (lastRange || total != null && position >= total ||
                        total == null && position < requestedEnd
                    ) return -1
                    if (!next()) return -1
                }
            }

            private fun next(): Boolean {
                val call = client.newCall(rangeRequest(request, position, total))
                current = call
                if (canceled) call.cancel()
                val next = call.execute()
                rangeStart = position
                requestedEnd = position + chunkBytes
                when {
                    next.code == 206 -> {
                        val range = ContentRange.parse(next.header("Content-Range"))
                        if (range == null || range.first != position) {
                            next.close()
                            throw ProtocolException("Unexpected Content-Range for bytes from $position")
                        }
                    }
                    // The server stopped honouring ranges: the rest of its whole file.
                    next.code == 200 -> {
                        try {
                            next.body!!.source().skip(position)
                        } catch (error: IOException) {
                            next.close()
                            throw error
                        }
                        lastRange = true
                    }
                    next.code == 416 && total == null -> {
                        next.close()
                        return false
                    }
                    else -> {
                        next.close()
                        throw RangeResponseException(next.code, position)
                    }
                }
                response = next
                return true
            }

            override fun timeout(): Timeout = response.body!!.source().timeout()

            override fun close() = response.close()
        }
    }

    private data class ContentRange(val first: Long, val last: Long, val total: Long?) {
        companion object {
            private val PATTERN = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")

            fun parse(header: String?): ContentRange? {
                val (first, last, total) = PATTERN.matchEntire(header?.trim() ?: return null)?.destructured
                    ?: return null
                return ContentRange(first.toLong(), last.toLong(), total.toLongOrNull())
            }
        }
    }

    companion object {
        /** yt-dlp's `http_chunk_size` for YouTube. */
        const val CHUNK_BYTES = 10L * 1024 * 1024

        private val OPEN_RANGE = Regex("""bytes=(\d+)-""")

        /** Where an open-ended GET for a googlevideo URL starts, or null to leave it alone. */
        private fun openEndedStart(request: Request): Long? {
            if (request.method != "GET" || !request.url.isGoogleVideo()) return null
            // A `range` parameter already bounds the response.
            if (request.url.queryParameter("range") != null) return null
            val range = request.header("Range") ?: return 0
            return OPEN_RANGE.matchEntire(range.trim())?.groupValues?.get(1)?.toLongOrNull()
        }

        private fun HttpUrl.isGoogleVideo() = host == "googlevideo.com" || host.endsWith(".googlevideo.com")
    }
}

/** A later range of a stream was refused with [responseCode]; see [isHttpForbidden]. */
internal class RangeResponseException(val responseCode: Int, position: Long) :
    IOException("Response code $responseCode for the range from byte $position")
