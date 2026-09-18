package io.github.originalrecipe1.unfurlit.data.history

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.github.originalrecipe1.unfurlit.domain.repository.HistoryRepository
import okio.Buffer

internal data class StoredHistoryThumbnail(val id: Long)

/** Reads only requested artwork; the list query never brings image blobs into memory. */
internal class HistoryThumbnailFetcher(
    private val id: Long,
    private val repository: HistoryRepository,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): SourceFetchResult? {
        val bytes = repository.loadThumbnail(id) ?: return null
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), options.fileSystem),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val repository: HistoryRepository) : Fetcher.Factory<StoredHistoryThumbnail> {
        override fun create(data: StoredHistoryThumbnail, options: Options, imageLoader: ImageLoader) =
            HistoryThumbnailFetcher(data.id, repository, options)
    }
}
