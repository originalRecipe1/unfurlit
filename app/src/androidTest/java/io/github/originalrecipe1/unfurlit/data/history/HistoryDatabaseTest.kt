package io.github.originalrecipe1.unfurlit.data.history

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originalrecipe1.unfurlit.domain.model.HistoryEntry
import io.github.originalrecipe1.unfurlit.domain.model.HistoryMediaKind
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HistoryDatabaseTest {
    @Test
    fun upgradePreservesVisitsAndThumbnailDeletionCannotResurrectThem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "history-migration-${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                old.execSQL("""
                    CREATE TABLE history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, source_url TEXT NOT NULL,
                        platform TEXT, title TEXT, author TEXT, media_kind TEXT NOT NULL,
                        media_count INTEGER NOT NULL, duration_seconds INTEGER, viewed_at INTEGER NOT NULL
                    )
                """.trimIndent())
                old.execSQL("INSERT INTO history VALUES (1, 'https://example.com/post', 'Example', 'Saved visit', NULL, 'Video', 1, 62, 1234)")
                old.version = 1
            }
            val bytes = byteArrayOf(1, 2, 3)
            HistoryDatabase(context, name).use { database ->
                val oldEntry = database.readPage(50).single()
                assertEquals("Saved visit", oldEntry.title)
                assertEquals(1234L, oldEntry.viewedAtEpochMillis)
                assertNull(oldEntry.thumbnail)
                database.updateThumbnail(oldEntry.id, bytes)
            }
            HistoryDatabase(context, name).use { database ->
                val entry = database.readPage(50).single()
                assertTrue(entry.hasThumbnail)
                assertNull("List queries must not load thumbnail blobs", entry.thumbnail)
                assertArrayEquals(bytes, database.readThumbnail(entry.id))
                database.delete(1)
                database.updateThumbnail(1, bytes)
                assertNull(database.readThumbnail(1))
                assertTrue(database.readPage(50).isEmpty())
                val newId = database.insert(io.github.originalrecipe1.unfurlit.domain.model.HistoryEntry(
                    0, "https://example.com/new", null, null, null,
                    io.github.originalrecipe1.unfurlit.domain.model.HistoryMediaKind.Image, 1, null, 4567,
                ))
                database.updateThumbnail(newId, bytes)
                database.clear()
                assertTrue(database.readPage(50).isEmpty())
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun recordReplacesEarlierVisitsToTheSameLinkAndKeepsTheirThumbnail() {
        withDatabase { database ->
            val bytes = byteArrayOf(4, 5, 6)
            val first = database.record(visit("https://example.com/post", viewedAt = 1))
            database.updateThumbnail(first, bytes)
            database.record(visit("https://example.com/other", viewedAt = 2))
            val again = database.record(visit("https://example.com/post", viewedAt = 3))

            val entries = database.readPage(50)
            assertEquals(listOf("https://example.com/post", "https://example.com/other"), entries.map { it.sourceUrl })
            assertEquals(again, entries.first().id)
            assertArrayEquals(bytes, database.readThumbnail(again))
            assertNull(database.readThumbnail(first))
        }
    }

    @Test
    fun recordDropsTheOldestVisitsBeyondTheLimit() {
        withDatabase { database ->
            repeat(5) { index -> database.record(visit("https://example.com/$index", viewedAt = index.toLong()), maxEntries = 3) }
            assertEquals(
                listOf("https://example.com/4", "https://example.com/3", "https://example.com/2"),
                database.readPage(50).map { it.sourceUrl },
            )
        }
    }

    private fun visit(url: String, viewedAt: Long) = HistoryEntry(0, url, null, null, null, HistoryMediaKind.Video, 1, null, viewedAt)

    private fun withDatabase(test: (HistoryDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "history-record-${UUID.randomUUID()}.db"
        try { HistoryDatabase(context, name).use(test) } finally { context.deleteDatabase(name) }
    }
}
