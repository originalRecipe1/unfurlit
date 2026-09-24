package io.github.originalrecipe1.unfurlit.data.extractor.ytdlp

import io.github.originalrecipe1.unfurlit.data.extractor.MediaRequestHeaders
import org.json.JSONArray
import org.json.JSONObject
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.domain.model.PlaybackSource
import io.github.originalrecipe1.unfurlit.domain.model.StreamFormat
import io.github.originalrecipe1.unfurlit.util.UrlValidator
import java.net.URI

internal object YtDlpJsonParser {
    fun parse(sourceUrl: String, json: String): ExtractionResult {
        if (json.length > MAX_JSON_LENGTH) {
            throw ExtractionException(ExtractionError.ExtractionFailed)
        }
        val root = parseRoot(json)
        val rootHeaders = root.optStringMap("http_headers")
        val entries = root.optJSONArray("entries")
            ?.objects(MAX_MEDIA_ENTRIES)
        val mediaNodes = entries ?: listOf(root)
        val media = mediaNodes.mapNotNull { entry ->
            entry.toExtractedMedia(rootHeaders)
        }
        if (media.isEmpty()) {
            throw ExtractionException(ExtractionError.MediaUnavailable)
        }

        val metadataFallback = entries?.firstOrNull()
        return ExtractionResult(
            sourceUrl = sourceUrl,
            platform = root.optStringOrNull("extractor_key")
                ?: root.optStringOrNull("extractor")
                ?: metadataFallback?.optStringOrNull("extractor_key")
                ?: metadataFallback?.optStringOrNull("extractor"),
            title = root.optStringOrNull("playlist_title")
                ?: root.optStringOrNull("title")
                ?: metadataFallback?.optStringOrNull("title"),
            author = root.optStringOrNull("playlist_uploader")
                ?: root.authorOrNull()
                ?: metadataFallback?.authorOrNull(),
            description = root.optStringOrNull("playlist_description")
                ?: root.optStringOrNull("description")
                ?: metadataFallback?.optStringOrNull("description"),
            thumbnailUrl = root.safeUrlOrNull("thumbnail")
                ?: metadataFallback?.safeUrlOrNull("thumbnail"),
            media = media,
        )
    }

    private fun parseRoot(json: String): JSONObject {
        val trimmed = json.trim()
        val lines = trimmed.lineSequence()
            .filter(String::isNotBlank)
            .take(MAX_MEDIA_ENTRIES + 1)
            .toList()
        if (lines.size > 1 && lines.all { line ->
                line.trimStart().startsWith('{') && line.trimEnd().endsWith('}')
            }
        ) {
            return jsonLinesRoot(lines)
        }
        return runCatching { JSONObject(trimmed) }
            .getOrElse { throw ExtractionException(ExtractionError.ExtractionFailed, it) }
    }

    private fun jsonLinesRoot(lines: List<String>): JSONObject {
        val entries = lines.map { line ->
            if (line.length > MAX_JSON_LINE_LENGTH) {
                throw ExtractionException(ExtractionError.ExtractionFailed)
            }
            runCatching { JSONObject(line) }.getOrElse {
                throw ExtractionException(ExtractionError.ExtractionFailed, it)
            }
        }
        if (entries.isEmpty() || entries.size > MAX_MEDIA_ENTRIES) {
            throw ExtractionException(ExtractionError.ExtractionFailed)
        }
        return JSONObject().apply {
            put("entries", JSONArray(entries))
            entries.first().optStringOrNull("playlist_title")?.let {
                put("title", it)
            }
            entries.first().optStringOrNull("playlist_uploader")?.let {
                put("uploader", it)
            }
            entries.first().optStringOrNull("playlist_description")?.let {
                put("description", it)
            }
        }
    }

    private fun JSONObject.toExtractedMedia(
        inheritedHeaders: Map<String, String>,
    ): ExtractedMedia? {
        val commonHeaders = inheritedHeaders + optStringMap("http_headers")
        val playbackFormats = selectedFormats()
            .filter { it.hasPlayableUrl() }
            .ifEmpty { selectAvailableFormats() }
        val directMedia = takeIf { it.hasPlayableUrl() }

        // File type is a stronger signal than an "unknown" codec reported by
        // generic extractors for direct image and audio resources.
        if (looksLikeImage()) {
            return toPlaybackSource(commonHeaders, MediaKind.Image)
                ?.let(ExtractedMedia::Image)
        }
        if (looksLikeAudio()) {
            return toPlaybackSource(commonHeaders, MediaKind.Audio)?.let { source ->
                ExtractedMedia.Audio(
                    source = source,
                    durationSeconds = durationSeconds(),
                    artworkUrl = safeUrlOrNull("thumbnail"),
                )
            }
        }

        val combined = playbackFormats.firstOrNull { it.hasVideo() && it.hasAudio() }
            ?: directMedia?.takeIf { it.hasVideo() && it.hasAudio() }
        val videoJson = combined
            ?: playbackFormats.firstOrNull { it.hasVideo() }
            ?: directMedia?.takeIf { it.hasVideo() }
        if (videoJson != null) {
            val videoSource = videoJson.toPlaybackSource(commonHeaders, MediaKind.Video)
                ?: return null
            val audioJson = if (combined == null && !videoJson.hasAudio()) {
                playbackFormats.firstOrNull { it.hasAudio() && !it.hasVideo() }
                    ?: directMedia?.takeIf {
                        it !== videoJson && it.hasAudio() && !it.hasVideo()
                    }
            } else {
                null
            }
            return ExtractedMedia.Video(
                videoSource = videoSource,
                audioSource = audioJson?.toPlaybackSource(commonHeaders, MediaKind.Audio),
                durationSeconds = durationSeconds(),
            )
        }

        val candidates = buildList {
            directMedia?.let(::add)
            addAll(playbackFormats)
        }
        candidates.firstOrNull { it.looksLikeImage() }?.let { imageJson ->
            return imageJson.toPlaybackSource(commonHeaders, MediaKind.Image)
                ?.let(ExtractedMedia::Image)
        }
        candidates.firstOrNull { it.hasAudio() || it.looksLikeAudio() }?.let { audioJson ->
            return audioJson.toPlaybackSource(commonHeaders, MediaKind.Audio)?.let { source ->
                ExtractedMedia.Audio(
                    source = source,
                    durationSeconds = durationSeconds(),
                    artworkUrl = safeUrlOrNull("thumbnail"),
                )
            }
        }

        // Some generic extractors omit codec fields for otherwise playable media.
        return (directMedia
            ?: playbackFormats.lastOrNull())
            ?.toPlaybackSource(commonHeaders, MediaKind.Video)
            ?.let { source ->
                ExtractedMedia.Video(
                    videoSource = source,
                    audioSource = null,
                    durationSeconds = durationSeconds(),
                )
            }
    }

    private fun JSONObject.toPlaybackSource(
        commonHeaders: Map<String, String>,
        mediaKind: MediaKind,
    ): PlaybackSource? {
        val streamUrl = optStringOrNull("url") ?: return null
        val uri = runCatching { URI(streamUrl) }.getOrNull() ?: return null
        if (!UrlValidator.isAllowedHttps(streamUrl)) return null

        val protocol = optStringOrNull("protocol").orEmpty().lowercase()
        val extension = mediaExtension(uri)
        val path = uri.path.orEmpty().lowercase()
        val streamFormat = when {
            protocol.contains("m3u8") || extension == "m3u8" || path.endsWith(".m3u8") ->
                StreamFormat.Hls
            protocol == "dash" || extension == "mpd" || path.endsWith(".mpd") ->
                StreamFormat.Dash
            else -> StreamFormat.Progressive
        }

        val mediaMimeType = when (streamFormat) {
            StreamFormat.Hls -> "application/x-mpegURL"
            StreamFormat.Dash -> "application/dash+xml"
            StreamFormat.Progressive -> optStringOrNull("mime_type")
                ?.takeIf(MIME_TYPE::matches)
                ?: progressiveMimeType(extension, mediaKind)
        }

        return PlaybackSource(
            url = streamUrl,
            headers = commonHeaders + optStringMap("http_headers"),
            format = streamFormat,
            mediaMimeType = mediaMimeType,
            formatId = optStringOrNull("format_id"),
            cookies = YtDlpCookies.parse(optStringOrNull("cookies"), streamUrl),
        )
    }

    private fun JSONObject.mediaExtension(uri: URI): String =
        optStringOrNull("ext")
            ?.lowercase()
            ?: uri.path.orEmpty()
                .substringAfterLast('.', missingDelimiterValue = "")
                .lowercase()

    private fun JSONObject.looksLikeImage(): Boolean {
        val mimeType = optStringOrNull("mime_type").orEmpty().lowercase()
        val extension = runCatching {
            URI(optStringOrNull("url").orEmpty())
        }.getOrNull()?.let { mediaExtension(it) }.orEmpty()
        return mimeType.startsWith("image/") || extension in IMAGE_EXTENSIONS
    }

    private fun JSONObject.looksLikeAudio(): Boolean {
        val mimeType = optStringOrNull("mime_type").orEmpty().lowercase()
        val extension = runCatching {
            URI(optStringOrNull("url").orEmpty())
        }.getOrNull()?.let { mediaExtension(it) }.orEmpty()
        return mimeType.startsWith("audio/") || extension in AUDIO_EXTENSIONS
    }

    private fun JSONObject.hasVideo(): Boolean {
        if (looksLikeImage()) return false
        val codec = optStringOrNull("vcodec")?.lowercase()
        val videoExtension = optStringOrNull("video_ext")?.lowercase()
        return codec != null && codec != "none" ||
            videoExtension != null && videoExtension != "none" ||
            optInt("width", 0) > 0 ||
            optInt("height", 0) > 0
    }

    private fun JSONObject.hasAudio(): Boolean {
        val codec = optStringOrNull("acodec")?.lowercase()
        val audioExtension = optStringOrNull("audio_ext")?.lowercase()
        return codec != null && codec != "none" ||
            audioExtension != null && audioExtension != "none"
    }

    private fun JSONObject.durationSeconds(): Long? =
        optDouble("duration", Double.NaN)
            .takeUnless(Double::isNaN)
            ?.takeIf { it >= 0.0 }
            ?.toLong()

    private fun JSONObject.authorOrNull(): String? =
        optStringOrNull("uploader")
            ?: optStringOrNull("channel")
            ?: optStringOrNull("creator")

    private fun JSONObject.safeUrlOrNull(key: String): String? =
        optStringOrNull(key)?.takeIf(UrlValidator::isAllowedHttps)

    private fun JSONObject.hasPlayableUrl(): Boolean =
        optStringOrNull("url")?.let(UrlValidator::isAllowedHttps) == true

    private fun JSONObject.optStringOrNull(key: String): String? =
        optString(key)
            .trim()
            .takeIf { it.isNotEmpty() && it != "null" && it != "none" }

    private fun JSONObject.optStringMap(key: String): Map<String, String> {
        val value = optJSONObject(key) ?: return emptyMap()
        return MediaRequestHeaders.sanitize(
            value.keys().asSequence().map { header -> header to value.optStringOrNull(header) },
        )
    }

    private fun JSONObject.selectedFormats(): List<JSONObject> = buildList {
        addAll(
            optJSONArray("requested_formats")
                ?.objects(MAX_FORMATS_PER_ENTRY)
                .orEmpty(),
        )
        optJSONArray("requested_downloads")
            ?.objects(MAX_FORMATS_PER_ENTRY)
            .orEmpty()
            .forEach { download ->
                val splitFormats = download.optJSONArray("requested_formats")
                    ?.objects(MAX_FORMATS_PER_ENTRY)
                    .orEmpty()
                if (splitFormats.isEmpty()) add(download) else addAll(splitFormats)
            }
    }.take(MAX_FORMATS_PER_ENTRY)

    private fun JSONObject.selectAvailableFormats(): List<JSONObject> {
        val available = optJSONArray("formats")
            ?.objects(MAX_AVAILABLE_FORMATS)
            .orEmpty()
            .filter { it.hasPlayableUrl() }
        if (available.isEmpty()) return emptyList()

        val video = available
            .filter { it.hasVideo() }
            .preferAtMost1080p()
            .maxWithOrNull(FORMAT_QUALITY)
        if (video != null) {
            if (video.hasAudio()) return listOf(video)
            val audio = available
                .filter { it.hasAudio() && !it.hasVideo() }
                .maxWithOrNull(FORMAT_QUALITY)
            return listOfNotNull(video, audio)
        }

        val audio = available
            .filter { it.hasAudio() }
            .maxWithOrNull(FORMAT_QUALITY)
        if (audio != null) return listOf(audio)

        val image = available.lastOrNull { it.looksLikeImage() }
        return listOfNotNull(image ?: available.lastOrNull())
    }

    private fun List<JSONObject>.preferAtMost1080p(): List<JSONObject> {
        val withinLimit = filter { format ->
            val height = format.optInt("height", 0)
            height == 0 || height <= MAX_VIDEO_HEIGHT
        }
        return withinLimit.ifEmpty { this }
    }

    private fun JSONArray.objects(limit: Int): List<JSONObject> = buildList {
        repeat(minOf(length(), limit)) { index ->
            optJSONObject(index)?.let(::add)
        }
    }

    private fun progressiveMimeType(extension: String, mediaKind: MediaKind): String? =
        when (extension) {
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "m4a" -> "audio/mp4"
            "webm" -> if (mediaKind == MediaKind.Audio) "audio/webm" else "video/webm"
            "mp3" -> "audio/mpeg"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "avif" -> "image/avif"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            else -> null
        }

    private enum class MediaKind {
        Video,
        Audio,
        Image,
    }

    private const val MAX_JSON_LENGTH = 2 * 1024 * 1024
    private const val MAX_JSON_LINE_LENGTH = 256 * 1024
    private const val MAX_MEDIA_ENTRIES = 50
    private const val MAX_FORMATS_PER_ENTRY = 32
    private const val MAX_AVAILABLE_FORMATS = 512
    private const val MAX_VIDEO_HEIGHT = 1080
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heic", "heif")
    private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "ogg", "oga", "opus", "wav", "flac", "aac")
    private val MIME_TYPE = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
    private val FORMAT_QUALITY = compareBy<JSONObject>(
        { it.optInt("height", 0) },
        { it.optDouble("tbr", 0.0) },
        { it.optDouble("abr", 0.0) },
        { it.optLong("filesize", 0L) },
    )
}
