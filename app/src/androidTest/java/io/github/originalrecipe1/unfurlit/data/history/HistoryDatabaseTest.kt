package io.github.originalrecipe1.unfurlit.data.history

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
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
}
