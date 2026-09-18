package io.github.originalrecipe1.unfurlit.domain.repository

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.domain.model.HistoryEntry

interface HistoryRepository {
    fun observeHistory(): Flow<PagingData<HistoryEntry>>

    suspend fun loadThumbnail(id: Long): ByteArray?

    suspend fun recordView(result: ExtractionResult)

    suspend fun remove(id: Long)

    suspend fun clear()
}
