package io.github.originalrecipe1.unfurlit.domain.model

data class ExtractionResult(
    val sourceUrl: String,
    val platform: String?,
    val title: String?,
    val author: String?,
    val description: String?,
    val thumbnailUrl: String?,
    val media: List<ExtractedMedia>,
    /** Optional soundtrack shared by the photos; not a separate gallery page. */
    val backgroundAudio: ExtractedMedia.Audio? = null,
)

sealed interface ExtractedMedia {
    data class Video(
        val videoSource: PlaybackSource,
        val audioSource: PlaybackSource?,
        val durationSeconds: Long?,
    ) : ExtractedMedia

    data class Image(
        val source: PlaybackSource,
    ) : ExtractedMedia

    data class Audio(
        val source: PlaybackSource,
        val durationSeconds: Long?,
        val artworkUrl: String?,
    ) : ExtractedMedia
}

data class PlaybackSource(
    val url: String,
    val headers: Map<String, String>,
    val format: StreamFormat,
    val mediaMimeType: String?,
    val formatId: String?,
    val cookies: List<PlaybackCookie> = emptyList(),
)

enum class StreamFormat {
    Progressive,
    Hls,
    Dash,
}

/** Ephemeral extractor cookies; never persisted with viewing history. */
data class PlaybackCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAtMillis: Long,
    val secure: Boolean,
    val hostOnly: Boolean,
) {
    override fun toString(): String = "PlaybackCookie(<redacted>)"
}
