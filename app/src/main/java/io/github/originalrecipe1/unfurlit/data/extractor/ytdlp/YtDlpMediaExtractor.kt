package io.github.originalrecipe1.unfurlit.data.extractor.ytdlp

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import io.github.originalrecipe1.unfurlit.data.network.UrlPreflight
import io.github.originalrecipe1.unfurlit.domain.extractor.MediaExtractor
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.util.SafeLog
import io.github.originalrecipe1.unfurlit.util.UrlValidator
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resumeWithException

class YtDlpMediaExtractor(
    context: Context,
) : MediaExtractor {
    private val appContext = context.applicationContext
    private val bundledYtDlpInstaller = BundledYtDlpInstaller(appContext)
    private val urlPreflight = UrlPreflight()

    override suspend fun extract(url: String): ExtractionResult {
        val secureInputUrl = UrlValidator.toHttpsUrl(url)
        if (secureInputUrl == null) {
            throw ExtractionException(ExtractionError.UnsupportedUrl)
        }

        return try {
            val extractionUrl = urlPreflight.resolve(secureInputUrl)
            val processId = "unfurlit-${UUID.randomUUID()}"
            val request = YoutubeDLRequest(extractionUrl).apply {
                addOption("--ignore-config")
                addOption("--skip-download")
                addOption("--playlist-end", MAX_MEDIA_ENTRIES.toString())
                addOption("--no-warnings")
                addOption("--format", FORMAT_SELECTOR)
                addCommands(
                    metadataLimits(
                        "title" to SHORT_METADATA_PATTERN,
                        "uploader" to SHORT_METADATA_PATTERN,
                        "channel" to SHORT_METADATA_PATTERN,
                        "creator" to SHORT_METADATA_PATTERN,
                        "description" to DESCRIPTION_PATTERN,
                        "playlist_title" to SHORT_METADATA_PATTERN,
                        "playlist_uploader" to SHORT_METADATA_PATTERN,
                        "playlist_description" to DESCRIPTION_PATTERN,
                    ),
                )
                addOption("--print", OUTPUT_TEMPLATE)
            }
            val output = withTimeout(EXTRACTION_TIMEOUT_MILLIS) {
                executeCancellable(request, processId)
            }
            YtDlpJsonParser.parse(url, output).also { result ->
                Log.d(
                    TAG,
                    "Extracted platform=${result.platform}, mediaCount=${result.media.size}",
                )
            }
        } catch (error: ExtractionException) {
            logFailure(error)
            throw error
        } catch (error: TimeoutCancellationException) {
            throw ExtractionException(ExtractionError.NetworkFailure, error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logFailure(error)
            throw ExtractionException(error.toDomainError(), error)
        }
    }

    private suspend fun executeCancellable(
        request: YoutubeDLRequest,
        processId: String,
    ): String = suspendCancellableCoroutine { continuation ->
        val future = executor.submit {
            try {
                ensureInitialized()
                val output = YoutubeDL.getInstance().execute(request, processId).out
                continuation.resumeWith(Result.success(output))
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }

        continuation.invokeOnCancellation {
            future.cancel(true)
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        }
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(initializationLock) {
            if (!initialized) {
                bundledYtDlpInstaller.ensureCurrent()
                YoutubeDL.getInstance().init(appContext)
                initialized = true
            }
        }
    }

    private fun logFailure(error: Throwable) {
        val detail = generateSequence(error) { it.cause }
            .mapNotNull(Throwable::message)
            .joinToString(" | ")
        Log.e(
            TAG,
            "Extraction failed (${error::class.java.simpleName}): ${SafeLog.redact(detail)}",
        )
    }

    private fun metadataLimits(vararg limits: Pair<String, String>): List<String> =
        limits.flatMap { (field, pattern) ->
            listOf("--replace-in-metadata", field, pattern, "\\1")
        }

    private companion object {
        const val TAG = "YtDlpExtractor"
        const val FORMAT_SELECTOR =
            "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best"
        const val OUTPUT_TEMPLATE =
            "%(.{extractor_key,extractor,title,uploader,channel,creator,description," +
                "thumbnail,duration,url,protocol,ext,mime_type,vcodec,acodec,width," +
                "height,video_ext,audio_ext,http_headers,cookies,format_id,requested_formats," +
                "requested_downloads,playlist_title,playlist_uploader," +
                "playlist_description})j"
        const val SHORT_METADATA_PATTERN = "(?s)^(.{0,512}).*"
        const val DESCRIPTION_PATTERN = "(?s)^(.{0,16384}).*"
        const val EXTRACTION_TIMEOUT_MILLIS = 120_000L
        const val MAX_MEDIA_ENTRIES = 50
        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "unfurlit-ytdlp").apply { isDaemon = true }
        }
        val initializationLock = Any()

        @Volatile
        var initialized = false
    }
}
