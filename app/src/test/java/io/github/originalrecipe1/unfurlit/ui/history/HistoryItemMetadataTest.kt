package io.github.originalrecipe1.unfurlit.ui.history

import androidx.paging.ItemSnapshotList
import io.github.originalrecipe1.unfurlit.domain.model.HistoryEntry
import io.github.originalrecipe1.unfurlit.domain.model.HistoryMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryItemMetadataTest {
    @Test
    fun retainedCallbacksTolerateEmptyAndShorterSnapshots() {
        val initialItems = listOf(HistoryListItem.Day(1_000)) + (1L..100L).map { visit(it) }
        val replacements = listOf(emptyList(), listOf(HistoryListItem.Day(2_000), visit(201)))

        for (replacement in replacements) {
            var snapshot = ItemSnapshotList<HistoryListItem>(0, 0, initialItems)
            val key = { index: Int -> snapshot.historyItemKey(index) }
            val contentType = { index: Int -> snapshot.historyItemContentType(index) }

            assertEquals("day-1000", key(0))
            assertEquals("day", contentType(0))
            for (index in listOf(1, 36, 70, 100)) {
                assertEquals("visit-$index", key(index))
                assertEquals("entry", contentType(index))
            }

            snapshot = ItemSnapshotList(0, 0, replacement)

            // Invoke the original callbacks with every now-stale layout index.
            for (index in replacement.size until initialItems.size) {
                assertEquals("pending-$index", key(index))
                assertEquals("pending", contentType(index))
                assertFalse(initialItems.any { it.key == key(index) })
            }
            if (replacement.isNotEmpty()) {
                assertEquals("day-2000", key(0))
                assertEquals("day", contentType(0))
                assertEquals("visit-201", key(1))
                assertEquals("entry", contentType(1))
            }
        }
    }

    @Test
    fun unloadedSlotsUsePendingKeysAndOneContentType() {
        val snapshot = ItemSnapshotList<HistoryListItem>(1, 2, listOf(HistoryListItem.Day(1_000), visit(1)))
        val accesses = mutableListOf<Int>()

        for (index in listOf(0, 3, 4)) {
            assertEquals("pending-$index", snapshot.historyItemKey(index))
            assertEquals("pending", snapshot.historyItemContentType(index))
            assertNull(snapshot.historyItemOrNull(index) { accesses += it; snapshot[it] })
        }
        assertEquals(listOf(0, 3, 4), accesses)
        assertEquals("day-1000", snapshot.historyItemKey(1))
        assertEquals("day", snapshot.historyItemContentType(1))
        assertEquals("visit-1", snapshot.historyItemKey(2))
        assertEquals("entry", snapshot.historyItemContentType(2))
    }

    @Test
    fun retainedContentCallbacksOnlyAccessIndicesInTheCurrentSnapshot() {
        val initialItems = listOf(HistoryListItem.Day(1_000)) + (1L..100L).map { visit(it) }
        val replacements = listOf(emptyList(), listOf(HistoryListItem.Day(2_000), visit(201)))

        for (replacement in replacements) {
            var snapshot = ItemSnapshotList<HistoryListItem>(0, 0, initialItems)
            val accesses = mutableListOf<Int>()
            val content = { index: Int ->
                snapshot.historyItemOrNull(index) { accesses += it; snapshot[it] }
            }
            assertEquals(initialItems[36], content(36))
            assertEquals(initialItems[70], content(70))

            snapshot = ItemSnapshotList(0, 0, replacement)

            for (index in replacement.size until initialItems.size) assertNull(content(index))
            assertEquals(listOf(36, 70), accesses)
            for (index in replacement.indices) assertEquals(replacement[index], content(index))
            // Valid accesses still reach Paging, including its load-hint side effect.
            assertEquals(listOf(36, 70) + replacement.indices, accesses)
        }
    }

    private fun visit(id: Long) = HistoryListItem.Visit(
        HistoryEntry(id, "https://example.com/$id", null, "Visit $id", null,
            HistoryMediaKind.Video, 1, null, 1_000),
        day = 1_000,
    )
}
