package io.github.originalrecipe1.unfurlit.ui.player

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GoogleVideoRangeCallsTest {
    private val server = MockWebServer()
    private val ranges = CopyOnWriteArrayList<String?>()
    private var data = Random(1).nextBytes(2_500)
    private var ignoreRange = false
    private var ignoreRangeAfterFirst = false
    private var unknownTotal = false
    private var forbiddenFrom = Long.MAX_VALUE

    // Every host resolves to the local server, so the googlevideo host check is the real one.
    private val client = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress())
        })
        .build()
    private val calls = GoogleVideoRangeCalls(client, chunkBytes = 1_000)

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                ranges += range
                val match = Regex("""bytes=(\d+)-(\d*)""").matchEntire(range.orEmpty())
                if (ignoreRange || match == null || ignoreRangeAfterFirst && ranges.size > 1) {
                    return MockResponse().setBody(Buffer().write(data))
                }
                val first = match.groupValues[1].toLong()
                val total = if (unknownTotal) "*" else data.size.toString()
                if (first >= forbiddenFrom) return MockResponse().setResponseCode(403)
                if (first >= data.size) {
                    return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */$total")
                }
                val last = minOf(match.groupValues[2].toLongOrNull() ?: Long.MAX_VALUE, data.size - 1L)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $first-$last/$total")
                    .setBody(Buffer().write(data, first.toInt(), (last - first + 1).toInt()))
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun request(range: String? = null, host: String = "rr1---sn-test.googlevideo.com") = Request.Builder()
        .url("http://$host:${server.port}/videoplayback?itag=251")
        .apply { range?.let { header("Range", it) } }
        .build()

    private fun Response.bytes() = use { checkNotNull(it.body).bytes() }

    @Test fun openEndedRequestIsReadInBoundedRanges() {
        val response = calls.newCall(request()).execute()

        assertEquals(206, response.code)
        assertEquals("2500", response.header("Content-Length"))
        assertEquals("bytes 0-2499/2500", response.header("Content-Range"))
        assertArrayEquals(data, response.bytes())
        assertEquals(listOf("bytes=0-999", "bytes=1000-1999", "bytes=2000-2499"), ranges)
    }

    @Test fun seekStartsARangeAtTheRequestedByte() {
        data = Random(2).nextBytes(3_500)

        val response = calls.newCall(request("bytes=1500-")).execute()

        assertEquals("bytes 1500-3499/3500", response.header("Content-Range"))
        assertEquals("2000", response.header("Content-Length"))
        assertArrayEquals(data.copyOfRange(1_500, 3_500), response.bytes())
        assertEquals(listOf("bytes=1500-2499", "bytes=2500-3499"), ranges)
    }

    @Test fun refusedLaterRangeFailsTheReadAsForbidden() {
        forbiddenFrom = 1_000
        val response = calls.newCall(request()).execute()
        assertEquals(206, response.code)
        try {
            response.bytes()
            fail("The refused second range was read")
        } catch (error: RangeResponseException) {
            assertEquals(403, error.responseCode)
            // As Media3 reports a read failure: wrapped in its own IOException.
            assertTrue(IOException(error).isHttpForbidden())
        }
        assertEquals(listOf("bytes=0-999", "bytes=1000-1999"), ranges)

        // A refused first range reaches the player as the response it is.
        assertEquals(403, calls.newCall(request("bytes=1000-")).execute().use { it.code })
    }

    @Test fun serverIgnoringRangeIsStreamedAsOneResponse() {
        ignoreRange = true

        val fromStart = calls.newCall(request()).execute()
        assertEquals(200, fromStart.code)
        assertArrayEquals(data, fromStart.bytes())

        // Media3 skips to the position itself in a 200, as it did before.
        val seek = calls.newCall(request("bytes=1500-")).execute()
        assertEquals(200, seek.code)
        assertArrayEquals(data, seek.bytes())
        assertEquals(listOf("bytes=0-999", "bytes=1500-2499"), ranges)
    }

    @Test fun serverIgnoringALaterRangeContinuesWithoutRepeatingBytes() {
        ignoreRangeAfterFirst = true

        val response = calls.newCall(request()).execute()

        assertEquals("2500", response.header("Content-Length"))
        assertArrayEquals(data, response.bytes())
        assertEquals(listOf("bytes=0-999", "bytes=1000-1999"), ranges)
    }

    @Test fun unknownTotalReadsUntilAShortRange() {
        unknownTotal = true

        val response = calls.newCall(request()).execute()

        assertNull(response.header("Content-Length"))
        assertNull(response.header("Content-Range"))
        assertEquals(-1L, checkNotNull(response.body).contentLength())
        assertArrayEquals(data, response.bytes())
        assertEquals(listOf("bytes=0-999", "bytes=1000-1999", "bytes=2000-2999"), ranges)
    }

    @Test fun unknownTotalEndingOnARangeBoundaryStopsAt416() {
        unknownTotal = true
        data = Random(3).nextBytes(2_000)

        assertArrayEquals(data, calls.newCall(request()).execute().bytes())
        assertEquals(listOf("bytes=0-999", "bytes=1000-1999", "bytes=2000-2999"), ranges)
    }

    @Test fun otherHostsAndBoundedRangesAreUnchanged() {
        assertArrayEquals(data, calls.newCall(request(host = "media.example")).execute().bytes())
        val bounded = calls.newCall(request("bytes=0-99")).execute()
        assertEquals("bytes 0-99/2500", bounded.header("Content-Range"))
        assertArrayEquals(data.copyOfRange(0, 100), bounded.bytes())
        assertEquals(listOf(null, "bytes=0-99"), ranges)
    }

    @Test fun enqueuedCallJoinsRangesLikeExecute() {
        // OkHttpDataSource enqueues its calls.
        val result = CompletableFuture<Response>()
        calls.newCall(request("bytes=500-")).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { result.completeExceptionally(e) }
            override fun onResponse(call: Call, response: Response) { result.complete(response) }
        })
        val response = result.get(10, TimeUnit.SECONDS)

        assertEquals("bytes 500-2499/2500", response.header("Content-Range"))
        assertArrayEquals(data.copyOfRange(500, 2_500), response.bytes())
        assertEquals(listOf("bytes=500-1499", "bytes=1500-2499"), ranges)
    }
}
