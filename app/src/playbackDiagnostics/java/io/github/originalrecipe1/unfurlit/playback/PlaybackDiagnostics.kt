package io.github.originalrecipe1.unfurlit.playback

import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug and linkCheck builds only (release builds compile a no-op): logs where the time goes
 * between a finished extraction and a video's first frame, under the `PlaybackTiming` tag.
 *
 * Times are milliseconds since the extraction finished. One `report` line per player follows
 * once the video has played [WINDOW_MS] past its first frame, or when the player is released.
 * Logs never contain a media URL, only its `mime` and `itag` parameters.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object PlaybackDiagnostics {
    /** Replaces the yt-dlp format selector while set, for codec comparisons (LivePlaybackTest). */
    @Volatile var formatOverride: String? = null

    /** Every report so far, newest last, for LivePlaybackTest. */
    val reports = CopyOnWriteArrayList<JSONObject>()

    @Volatile private var extractionFinishedAt = 0L

    fun formatSelector(default: String): String = formatOverride ?: default

    fun extractionFinished() {
        extractionFinishedAt = SystemClock.elapsedRealtime()
        log("extraction finished${formatOverride?.let { " (format override $it)" }.orEmpty()}")
    }

    /**
     * Call before preparing [player]. The returned listener times its media requests; pass it to
     * every data source of that player.
     */
    fun watch(player: ExoPlayer): TransferListener? = Session(player, extractionFinishedAt)

    private class Session(private val player: ExoPlayer, extractionFinishedAt: Long) :
        AnalyticsListener, TransferListener {
        private val id = Integer.toHexString(System.identityHashCode(player))
        private val origin = extractionFinishedAt.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
        private val handler = Handler(player.applicationLooper)
        private val streams = LinkedHashMap<String, Stream>()
        private val transfers = IdentityHashMap<DataSpec, Stream>()
        private val prepareMs = since()
        private var readyMs: Long? = null
        private var firstFrameMs: Long? = null
        private var firstFrameAt = 0L
        private var videoFormat: Format? = null
        private var videoDecoder: String? = null
        private var videoDecoderInitMs: Long? = null
        private var audioDecoder: String? = null
        private var counters: DecoderCounters? = null
        private var bitrateEstimate: Long? = null
        private var rebuffers = 0
        private var rebufferStartedAt = 0L
        private var rebufferMs = 0L
        private val loadErrors = mutableListOf<String>()
        private var reported = false

        private class Stream(val label: String) {
            var opens = 0
            var openedMs: Long? = null
            var firstByteMs: Long? = null
            var firstByteAt = 0L
            var bytes = 0L
            var bytesAtReady: Long? = null
            var bytesAtFirstFrame: Long? = null
        }

        init {
            player.addAnalyticsListener(this)
            log("$id prepare at +${prepareMs}ms")
        }

        private fun since() = SystemClock.elapsedRealtime() - origin

        // Loader threads.
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            val at = since()
            val label = label(dataSpec.uri.toString())
            val stream = synchronized(streams) {
                streams.getOrPut(label) { Stream(label) }.apply {
                    opens++
                    if (openedMs == null) openedMs = at
                    transfers[dataSpec] = this
                }
            }
            log("$id ${stream.label} open #${stream.opens} at +${at}ms, from byte ${dataSpec.position}" +
                if (dataSpec.length >= 0) ", ${dataSpec.length} bytes" else "")
        }

        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            val firstByte = synchronized(streams) {
                val stream = transfers[dataSpec] ?: return
                stream.bytes += bytesTransferred
                if (stream.firstByteMs != null) return
                stream.firstByteMs = since()
                stream.firstByteAt = SystemClock.elapsedRealtime()
                stream
            }
            log("$id ${firstByte.label} first byte at +${firstByte.firstByteMs}ms")
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            synchronized(streams) { transfers.remove(dataSpec) }
        }

        private fun label(url: String): String {
            val uri = url.toUri()
            val mime = uri.getQueryParameter("mime")
            val itag = uri.getQueryParameter("itag")
            return listOfNotNull(mime, itag?.let { "itag $it" }).joinToString(" ")
                .ifEmpty { uri.lastPathSegment?.takeLast(24) ?: "stream" }
        }

        // Application thread.
        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    if (rebufferStartedAt > 0) {
                        rebufferMs += SystemClock.elapsedRealtime() - rebufferStartedAt
                        rebufferStartedAt = 0
                    }
                    if (readyMs != null) return
                    readyMs = since()
                    synchronized(streams) { streams.values.forEach { it.bytesAtReady = it.bytes } }
                    log("$id ready at +${readyMs}ms; ${streamBytes()}")
                }
                Player.STATE_BUFFERING -> if (readyMs != null && !reported) {
                    rebuffers++
                    rebufferStartedAt = SystemClock.elapsedRealtime()
                    log("$id rebuffering at +${since()}ms with ${player.totalBufferedDuration}ms buffered, " +
                        "loading ${player.isLoading}; ${streamBytes()}")
                }
            }
        }

        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            if (firstFrameMs != null) return
            firstFrameMs = since()
            firstFrameAt = SystemClock.elapsedRealtime()
            synchronized(streams) { streams.values.forEach { it.bytesAtFirstFrame = it.bytes } }
            log("$id first frame at +${firstFrameMs}ms; ${streamBytes()}")
            handler.postDelayed(::report, WINDOW_MS)
            handler.postDelayed(::progress, PROGRESS_MS)
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            videoFormat = format
            log("$id video ${format.codecs} ${format.width}x${format.height} @${format.frameRate} fps, ${format.bitrate} bit/s")
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            videoDecoder = decoderName
            videoDecoderInitMs = initializationDurationMs
            log("$id video decoder $decoderName (hardware ${isHardware(decoderName)}) " +
                "initialized at +${since()}ms in ${initializationDurationMs}ms")
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            audioDecoder = decoderName
        }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) {
            this.bitrateEstimate = bitrateEstimate
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            val code = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            val description = "${label(loadEventInfo.uri.toString())} ${code ?: error.javaClass.simpleName} at +${since()}ms"
            loadErrors += description
            log("$id load error: $description")
        }

        override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
            counters = decoderCounters
        }

        override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) {
            handler.removeCallbacksAndMessages(null)
            report()
        }

        /** Every [PROGRESS_MS] within the window: what each stream has read and how far ahead. */
        private fun progress() {
            log("$id at +${since()}ms: position ${player.currentPosition}ms, " +
                "${player.totalBufferedDuration}ms buffered, loading ${player.isLoading}; ${streamBytes()}")
            handler.postDelayed(::progress, PROGRESS_MS)
        }

        private fun report() {
            if (reported) return
            reported = true
            handler.removeCallbacksAndMessages(null)
            if (rebufferStartedAt > 0) rebufferMs += SystemClock.elapsedRealtime() - rebufferStartedAt
            val now = SystemClock.elapsedRealtime()
            val counters = (player.videoDecoderCounters ?: counters)?.apply { ensureUpdated() }
            val format = videoFormat
            val report = JSONObject()
                .put("player", id)
                .put("formatOverride", formatOverride)
                .put("videoDecoder", videoDecoder)
                .put("videoDecoderHardware", videoDecoder?.let(::isHardware))
                .put("videoDecoderInitMs", videoDecoderInitMs)
                .put("audioDecoder", audioDecoder)
                // WebM leaves codecs empty, so VP9 is named by its MIME type there.
                .put("codecs", format?.let { it.codecs ?: it.sampleMimeType })
                .put("width", format?.width)
                .put("height", format?.height)
                .put("frameRate", format?.frameRate?.toDouble())
                .put("prepareMs", prepareMs)
                .put("readyMs", readyMs)
                .put("firstFrameMs", firstFrameMs)
                .put("bitrateEstimate", bitrateEstimate)
                .put("windowMs", if (firstFrameAt > 0) now - firstFrameAt else 0)
                .put("renderedFrames", counters?.renderedOutputBufferCount)
                .put("droppedFrames", counters?.droppedBufferCount)
                .put("maxConsecutiveDroppedFrames", counters?.maxConsecutiveDroppedBufferCount)
                .put("skippedFrames", counters?.skippedOutputBufferCount)
                .put("rebuffers", rebuffers)
                .put("rebufferMs", rebufferMs)
                .put("loadErrors", JSONArray(loadErrors))
                .put("streams", JSONArray().apply {
                    synchronized(streams) {
                        streams.values.forEach { stream ->
                            put(
                                JSONObject()
                                    .put("label", stream.label)
                                    .put("opens", stream.opens)
                                    .put("openedMs", stream.openedMs)
                                    .put("firstByteMs", stream.firstByteMs)
                                    .put("bytesAtReady", stream.bytesAtReady)
                                    .put("bytesAtFirstFrame", stream.bytesAtFirstFrame)
                                    .put("bytesAtReport", stream.bytes)
                                    .put(
                                        "kibPerSecond",
                                        if (stream.firstByteAt > 0 && now > stream.firstByteAt) {
                                            stream.bytes / 1.024 / (now - stream.firstByteAt)
                                        } else null,
                                    ),
                            )
                        }
                    }
                })
            reports += report
            log("$id report $report")
        }

        private fun streamBytes() = synchronized(streams) {
            streams.values.joinToString { "${it.label} ${it.bytes / 1024} KiB in ${it.opens} opens" }
        }
    }

    private fun isHardware(decoderName: String): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .firstOrNull { it.name == decoderName }?.isHardwareAccelerated
    }

    private fun log(message: String) = Log.i(TAG, message)

    private const val TAG = "PlaybackTiming"
    private const val WINDOW_MS = 30_000L
    private const val PROGRESS_MS = 2_000L
}
