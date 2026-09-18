package io.github.originalrecipe1.unfurlit.data.history

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.domain.model.HistoryEntry
import io.github.originalrecipe1.unfurlit.domain.repository.HistoryRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class HistoryThumbnailFetcherTest {
    @Test
    fun revisitingARowUsesMemoryCacheInsteadOfReadingAndDecodingAgain() = runBlocking {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)
            it.toByteArray()
        }
        bitmap.recycle()
        var reads = 0
        val repository = object : HistoryRepository {
            override fun observeHistory() = flowOf(androidx.paging.PagingData.empty<HistoryEntry>())
            override suspend fun loadThumbnail(id: Long): ByteArray {
                assertEquals(7L, id)
                reads++
                return bytes
            }
            override suspend fun recordView(result: ExtractionResult) = Unit
            override suspend fun remove(id: Long) = Unit
            override suspend fun clear() = Unit
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = ImageLoader.Builder(context)
            .components { add(HistoryThumbnailFetcher.Factory(repository)) }
            .build()
        try {
            fun request() = ImageRequest.Builder(context)
                .data(StoredHistoryThumbnail(7))
                .memoryCacheKey("history-7-1234")
                .size(192, 192)
                .build()
            assertTrue(loader.execute(request()) is SuccessResult)
            val second = loader.execute(request()) as SuccessResult
            assertEquals(DataSource.MEMORY_CACHE, second.dataSource)
            assertEquals(1, reads)
        } finally {
            loader.shutdown()
        }
    }
}
