package io.github.originalrecipe1.unfurlit.data.history

import androidx.paging.PagingConfig
import androidx.paging.PagingSource.LoadParams
import androidx.paging.PagingSource.LoadResult
import androidx.paging.PagingState
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originalrecipe1.unfurlit.domain.model.HistoryEntry
import io.github.originalrecipe1.unfurlit.domain.model.HistoryMediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HistoryPagingSourceTest {
    @Test
    fun tenThousandVisitsPageBothWaysWithoutDuplicatesIncludingTimestampTies() = runBlocking {
        withDatabase { database ->
            seed(database, 10_000)
            val source = HistoryPagingSource(database)
            var page = source.load(LoadParams.Refresh(null, 100, false)).page()
            assertEquals(100, page.data.size)
            assertNull(page.prevKey)
            val ids = page.data.map { it.id }.toMutableList()
            while (page.nextKey != null) {
                page = source.load(LoadParams.Append(page.nextKey!!, 50, false)).page()
                assertTrue(page.data.size <= 50)
                assertTrue(page.data.all { it.thumbnail == null })
                ids += page.data.map { it.id }
            }
            assertEquals((10_000L downTo 1L).toList(), ids)
            // Start from the oldest known row, like returning to pages dropped from memory.
            var key: HistoryKey? = HistoryKey(0, 1)
            val reversed = mutableListOf(1L)
            while (key != null) {
                page = source.load(LoadParams.Prepend(key, 50, false)).page()
                reversed += page.data.asReversed().map { it.id }
                key = page.prevKey
            }
            assertEquals((1L..10_000L).toList(), reversed)
        }
    }

    @Test
    fun refreshCentersOnAnchorAndSurvivesDeletedAnchorAndClearedDatabase() = runBlocking {
        withDatabase { database ->
            seed(database, 300)
            val source = HistoryPagingSource(database)
            val page = source.load(LoadParams.Refresh(HistoryKey(1, 150), 100, false)).page()
            assertEquals((200L downTo 101L).toList(), page.data.map { it.id })
            val refreshKey = source.getRefreshKey(PagingState(listOf(page), 50, PagingConfig(50), 0))
            assertEquals(HistoryKey(1, 150), refreshKey)
            database.delete(150)
            val refreshed = source.load(LoadParams.Refresh(refreshKey, 100, false)).page()
            assertEquals(100, refreshed.data.size)
            assertFalse(refreshed.data.any { it.id == 150L })
            assertNotNull(refreshed.prevKey)
            assertNotNull(refreshed.nextKey)
            database.clear()
            val empty = source.load(LoadParams.Refresh(refreshKey, 100, false)).page()
            assertTrue(empty.data.isEmpty())
            assertNull(empty.prevKey)
            assertNull(empty.nextKey)
            source.invalidate()
            assertTrue(source.load(LoadParams.Refresh(null, 100, false)) is LoadResult.Invalid)
        }
    }

    @Test
    fun databaseFailureBecomesRetryableLoadError() = runBlocking {
        withDatabase { database ->
            database.writableDatabase.execSQL("DROP TABLE history")
            assertTrue(HistoryPagingSource(database).load(LoadParams.Refresh(null, 100, false)) is LoadResult.Error)
        }
    }

    private suspend fun withDatabase(test: suspend (HistoryDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "history-paging-${UUID.randomUUID()}.db"
        try { HistoryDatabase(context, name).use { test(it) } } finally { context.deleteDatabase(name) }
    }

    private fun seed(database: HistoryDatabase, count: Int) {
        database.writableDatabase.beginTransaction()
        try {
            repeat(count) { index ->
                database.insert(HistoryEntry(0, "https://example.com/$index", null, "Visit $index", null,
                    HistoryMediaKind.Video, 1, null, index.toLong() / 100))
            }
            database.writableDatabase.setTransactionSuccessful()
        } finally { database.writableDatabase.endTransaction() }
    }

    private fun LoadResult<HistoryKey, HistoryEntry>.page() = this as LoadResult.Page<HistoryKey, HistoryEntry>
}
