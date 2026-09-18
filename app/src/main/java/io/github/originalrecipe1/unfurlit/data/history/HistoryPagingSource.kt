package io.github.originalrecipe1.unfurlit.data.history

import androidx.paging.PagingSource
import androidx.paging.PagingState
import io.github.originalrecipe1.unfurlit.domain.model.HistoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class HistoryKey(val viewedAt: Long, val id: Long) {
    companion object {
        fun from(entry: HistoryEntry) = HistoryKey(entry.viewedAtEpochMillis, entry.id)
    }
}

internal class HistoryPagingSource(private val database: HistoryDatabase) : PagingSource<HistoryKey, HistoryEntry>() {
    override fun getRefreshKey(state: PagingState<HistoryKey, HistoryEntry>): HistoryKey? =
        state.anchorPosition?.let(state::closestItemToPosition)?.let(HistoryKey::from)

    override suspend fun load(params: LoadParams<HistoryKey>): LoadResult<HistoryKey, HistoryEntry> = withContext(Dispatchers.IO) {
        try {
            val page = database.readSnapshot {
                when (params) {
                    is LoadParams.Refresh -> {
                        val key = params.key
                        val newerLimit = params.loadSize / 2
                        val newer = if (key == null || newerLimit == 0) emptyList() else database.readPage(newerLimit, key, newer = true).asReversed()
                        val olderLimit = params.loadSize - newer.size
                        val older = if (olderLimit == 0) emptyList() else database.readPage(olderLimit, key, inclusive = true)
                        val entries = newer + older
                        LoadResult.Page(
                            data = entries,
                            prevKey = entries.firstOrNull()?.takeIf { key != null && newer.size == newerLimit }?.let(HistoryKey::from),
                            nextKey = entries.lastOrNull()?.takeIf { older.size == olderLimit }?.let(HistoryKey::from),
                        )
                    }
                    is LoadParams.Append -> {
                        val entries = database.readPage(params.loadSize, params.key)
                        LoadResult.Page(
                            data = entries,
                            prevKey = entries.firstOrNull()?.let(HistoryKey::from),
                            nextKey = entries.lastOrNull()?.takeIf { entries.size == params.loadSize }?.let(HistoryKey::from),
                        )
                    }
                    is LoadParams.Prepend -> {
                        val entries = database.readPage(params.loadSize, params.key, newer = true).asReversed()
                        LoadResult.Page(
                            data = entries,
                            prevKey = entries.firstOrNull()?.takeIf { entries.size == params.loadSize }?.let(HistoryKey::from),
                            nextKey = entries.lastOrNull()?.let(HistoryKey::from),
                        )
                    }
                }
            }
            if (invalid) LoadResult.Invalid() else page
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LoadResult.Error(error)
        }
    }
}
