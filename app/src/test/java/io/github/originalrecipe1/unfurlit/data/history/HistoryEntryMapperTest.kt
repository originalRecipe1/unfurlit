package io.github.originalrecipe1.unfurlit.data.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.domain.model.HistoryMediaKind
import io.github.originalrecipe1.unfurlit.domain.model.PlaybackSource
import io.github.originalrecipe1.unfurlit.domain.model.StreamFormat

class HistoryEntryMapperTest {
    @Test
    fun `maps video metadata without playback credentials`() {
        val entry = HistoryEntryMapper.fromExtraction(
            result = ExtractionResult(
                sourceUrl = "https://social.example/post/42",
                platform = "Example",
                title = "A title",
                author = "A creator",
                description = "A caption that is not persisted in history",
                thumbnailUrl = "https://cdn.example/thumbnail.jpg?token=secret",
                media = listOf(
                    ExtractedMedia.Video(
                        videoSource = source(
                            url = "https://cdn.example/video.mp4?token=secret",
                            headers = mapOf("Authorization" to "secret"),
                        ),
                        audioSource = null,
                        durationSeconds = 62,
                    ),
                ),
            ),
            viewedAtEpochMillis = 1234,
        )

        assertEquals("https://social.example/post/42", entry.sourceUrl)
        assertEquals(HistoryMediaKind.Video, entry.mediaKind)
        assertEquals(62L, entry.durationSeconds)
        assertEquals(1234L, entry.viewedAtEpochMillis)
        assertNull(entry.thumbnail)
        assertEquals(
            setOf(
                "id",
                "sourceUrl",
                "platform",
                "title",
                "author",
                "mediaKind",
                "mediaCount",
                "durationSeconds",
                "viewedAtEpochMillis",
                "thumbnail",
                "hasThumbnail",
            ),
            entry::class.java.declaredFields
                .map { it.name }
                .filterNot { it.startsWith("$") }
                .toSet(),
        )
    }

    @Test
    fun `recognizes an image gallery`() {
        val entry = HistoryEntryMapper.fromExtraction(
            result = ExtractionResult(
                sourceUrl = "https://social.example/gallery/1",
                platform = null,
                title = null,
                author = null,
                description = null,
                thumbnailUrl = null,
                media = listOf(
                    ExtractedMedia.Image(source("https://cdn.example/one.jpg")),
                    ExtractedMedia.Image(source("https://cdn.example/two.jpg")),
                ),
            ),
            viewedAtEpochMillis = 1,
        )

        assertEquals(HistoryMediaKind.Gallery, entry.mediaKind)
        assertEquals(2, entry.mediaCount)
        assertNull(entry.durationSeconds)
    }

    private fun source(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ) = PlaybackSource(
        url = url,
        headers = headers,
        format = StreamFormat.Progressive,
        mediaMimeType = null,
        formatId = null,
    )
}
