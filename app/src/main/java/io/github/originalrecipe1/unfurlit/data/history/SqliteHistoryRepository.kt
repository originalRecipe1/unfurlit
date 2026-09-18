package io.github.originalrecipe1.unfurlit.data.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow
import androidx.paging.InvalidatingPagingSourceFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.domain.model.HistoryEntry
import io.github.originalrecipe1.unfurlit.domain.model.HistoryMediaKind
import io.github.originalrecipe1.unfurlit.domain.repository.HistoryRepository

class SqliteHistoryRepository internal constructor(
    context: Context,
    private val database: HistoryDatabase,
) : HistoryRepository {
    constructor(context: Context) : this(context, HistoryDatabase(context.applicationContext))
    private val thumbnails = HistoryThumbnailLoader(context.applicationContext)
    private val changes = MutableStateFlow(0L)
    private val writeMutex = Mutex()

    override fun observeHistory(): Flow<PagingData<HistoryEntry>> = flow {
        coroutineScope {
            val sources = InvalidatingPagingSourceFactory { HistoryPagingSource(database) }
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                changes.drop(1).collect { sources.invalidate() }
            }
            try {
                emitAll(Pager(HISTORY_PAGING_CONFIG, pagingSourceFactory = sources).flow)
            } finally {
                observer.cancel()
                sources.invalidate()
            }
        }
    }

    override suspend fun loadThumbnail(id: Long): ByteArray? = withContext(Dispatchers.IO) {
        database.readThumbnail(id)
    }

    override suspend fun recordView(result: ExtractionResult) {
        val entry = HistoryEntryMapper.fromExtraction(
            result = result,
            viewedAtEpochMillis = System.currentTimeMillis(),
        )
        withContext(Dispatchers.IO) {
            val id = writeMutex.withLock {
                database.insert(entry).also { changes.value += 1 }
            }
            // Record immediately; a slow or unavailable preview must not delay history.
            val thumbnail = thumbnails.load(result) ?: return@withContext
            writeMutex.withLock {
                // UPDATE cannot recreate an entry removed while its preview was loading.
                database.updateThumbnail(id, thumbnail)
                changes.value += 1
            }
        }
    }

    override suspend fun remove(id: Long) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                database.delete(id)
                changes.value += 1
            }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                database.clear()
                changes.value += 1
            }
        }
    }
}

internal val HISTORY_PAGING_CONFIG = PagingConfig(
    pageSize = 50,
    initialLoadSize = 100,
    prefetchDistance = 15,
    maxSize = 250,
    enablePlaceholders = false,
)

internal class HistoryDatabase(
    context: Context,
    name: String = DATABASE_NAME,
) : SQLiteOpenHelper(context, name, null, DATABASE_VERSION) {
    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE $TABLE_HISTORY (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_SOURCE_URL TEXT NOT NULL,
                $COLUMN_PLATFORM TEXT,
                $COLUMN_TITLE TEXT,
                $COLUMN_AUTHOR TEXT,
                $COLUMN_MEDIA_KIND TEXT NOT NULL,
                $COLUMN_MEDIA_COUNT INTEGER NOT NULL,
                $COLUMN_DURATION_SECONDS INTEGER,
                $COLUMN_VIEWED_AT INTEGER NOT NULL,
                $COLUMN_THUMBNAIL BLOB
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX history_viewed_at ON $TABLE_HISTORY " +
                "($COLUMN_VIEWED_AT DESC, $COLUMN_ID DESC)",
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            database.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_THUMBNAIL BLOB")
        }
    }

    fun insert(entry: HistoryEntry): Long {
        val values = ContentValues().apply {
            put(COLUMN_SOURCE_URL, entry.sourceUrl)
            put(COLUMN_PLATFORM, entry.platform)
            put(COLUMN_TITLE, entry.title)
            put(COLUMN_AUTHOR, entry.author)
            put(COLUMN_MEDIA_KIND, entry.mediaKind.name)
            put(COLUMN_MEDIA_COUNT, entry.mediaCount)
            entry.durationSeconds?.let { put(COLUMN_DURATION_SECONDS, it) }
            put(COLUMN_VIEWED_AT, entry.viewedAtEpochMillis)
        }
        return writableDatabase.insertOrThrow(TABLE_HISTORY, null, values)
    }

    fun updateThumbnail(id: Long, thumbnail: ByteArray) {
        writableDatabase.update(
            TABLE_HISTORY,
            ContentValues().apply { put(COLUMN_THUMBNAIL, thumbnail) },
            "$COLUMN_ID = ?",
            arrayOf(id.toString()),
        )
    }

    fun delete(id: Long) {
        writableDatabase.delete(
            TABLE_HISTORY,
            "$COLUMN_ID = ?",
            arrayOf(id.toString()),
        )
    }

    fun clear() {
        writableDatabase.delete(TABLE_HISTORY, null, null)
    }

    fun readThumbnail(id: Long): ByteArray? = readableDatabase.query(
        TABLE_HISTORY, arrayOf(COLUMN_THUMBNAIL), "$COLUMN_ID = ?", arrayOf(id.toString()),
        null, null, null,
    ).use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getBlob(0) else null
    }

    // Two index seeks also handle large groups with identical timestamps efficiently,
    // without row-value comparisons (unavailable on Android 7's SQLite).
    fun readPage(limit: Int, key: HistoryKey? = null, newer: Boolean = false, inclusive: Boolean = false): List<HistoryEntry> {
        require(limit > 0)
        val direction = if (newer) "ASC" else "DESC"
        val order = "$COLUMN_VIEWED_AT $direction, $COLUMN_ID $direction"
        if (key == null) return queryPage(null, null, order, limit)
        val comparison = if (newer) ">" else "<"
        val idComparison = comparison + if (inclusive) "=" else ""
        val sameTime = queryPage(
            "$COLUMN_VIEWED_AT = ? AND $COLUMN_ID $idComparison ?",
            arrayOf(key.viewedAt.toString(), key.id.toString()), order, limit,
        )
        if (sameTime.size == limit) return sameTime
        return sameTime + queryPage(
            "$COLUMN_VIEWED_AT $comparison ?", arrayOf(key.viewedAt.toString()),
            order, limit - sameTime.size,
        )
    }

    fun <T> readSnapshot(block: () -> T): T {
        val database = readableDatabase
        database.beginTransactionNonExclusive()
        try {
            return block().also { database.setTransactionSuccessful() }
        } finally {
            database.endTransaction()
        }
    }

    private fun queryPage(selection: String?, args: Array<String>?, order: String, limit: Int): List<HistoryEntry> = readableDatabase.query(
        TABLE_HISTORY, HISTORY_COLUMNS, selection, args, null, null, order, limit.toString(),
    ).use { cursor ->
        buildList {
            val idIndex = cursor.getColumnIndexOrThrow(COLUMN_ID)
            val sourceUrlIndex = cursor.getColumnIndexOrThrow(COLUMN_SOURCE_URL)
            val platformIndex = cursor.getColumnIndexOrThrow(COLUMN_PLATFORM)
            val titleIndex = cursor.getColumnIndexOrThrow(COLUMN_TITLE)
            val authorIndex = cursor.getColumnIndexOrThrow(COLUMN_AUTHOR)
            val mediaKindIndex = cursor.getColumnIndexOrThrow(COLUMN_MEDIA_KIND)
            val mediaCountIndex = cursor.getColumnIndexOrThrow(COLUMN_MEDIA_COUNT)
            val durationIndex = cursor.getColumnIndexOrThrow(COLUMN_DURATION_SECONDS)
            val viewedAtIndex = cursor.getColumnIndexOrThrow(COLUMN_VIEWED_AT)
            val thumbnailIndex = cursor.getColumnIndexOrThrow("has_thumbnail")

            while (cursor.moveToNext()) {
                add(
                    HistoryEntry(
                        id = cursor.getLong(idIndex),
                        sourceUrl = cursor.getString(sourceUrlIndex),
                        platform = cursor.nullableString(platformIndex),
                        title = cursor.nullableString(titleIndex),
                        author = cursor.nullableString(authorIndex),
                        mediaKind = cursor.getString(mediaKindIndex).toHistoryKind(),
                        mediaCount = cursor.getInt(mediaCountIndex),
                        durationSeconds = cursor.nullableLong(durationIndex),
                        viewedAtEpochMillis = cursor.getLong(viewedAtIndex),
                        hasThumbnail = cursor.getInt(thumbnailIndex) != 0,
                    ),
                )
            }
        }
    }

    private fun android.database.Cursor.nullableString(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun android.database.Cursor.nullableLong(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun String.toHistoryKind(): HistoryMediaKind =
        HistoryMediaKind.entries.firstOrNull { it.name == this } ?: HistoryMediaKind.Mixed

    private companion object {
        const val DATABASE_NAME = "unfurlit-history.db"
        const val DATABASE_VERSION = 2
        const val TABLE_HISTORY = "history"
        const val COLUMN_ID = "id"
        const val COLUMN_SOURCE_URL = "source_url"
        const val COLUMN_PLATFORM = "platform"
        const val COLUMN_TITLE = "title"
        const val COLUMN_AUTHOR = "author"
        const val COLUMN_MEDIA_KIND = "media_kind"
        const val COLUMN_MEDIA_COUNT = "media_count"
        const val COLUMN_DURATION_SECONDS = "duration_seconds"
        const val COLUMN_VIEWED_AT = "viewed_at"
        const val COLUMN_THUMBNAIL = "thumbnail"
        val HISTORY_COLUMNS = arrayOf(
            COLUMN_ID,
            COLUMN_SOURCE_URL,
            COLUMN_PLATFORM,
            COLUMN_TITLE,
            COLUMN_AUTHOR,
            COLUMN_MEDIA_KIND,
            COLUMN_MEDIA_COUNT,
            COLUMN_DURATION_SECONDS,
            COLUMN_VIEWED_AT,
            "($COLUMN_THUMBNAIL IS NOT NULL) AS has_thumbnail",
        )
    }
}
