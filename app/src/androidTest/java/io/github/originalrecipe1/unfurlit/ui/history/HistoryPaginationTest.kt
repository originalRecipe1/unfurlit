package io.github.originalrecipe1.unfurlit.ui.history

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originalrecipe1.unfurlit.data.history.HistoryDatabase
import io.github.originalrecipe1.unfurlit.data.history.SqliteHistoryRepository
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.domain.model.HistoryEntry
import io.github.originalrecipe1.unfurlit.domain.model.HistoryMediaKind
import io.github.originalrecipe1.unfurlit.domain.repository.HistoryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class HistoryPaginationTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "history-ui-paging-${UUID.randomUUID()}.db"
    private val database = HistoryDatabase(context, name)
    private val repository = SqliteHistoryRepository(context, database)
    private val now = System.currentTimeMillis()
    private val viewModels = ViewModelStore()

    @After fun closeDatabase() {
        composeRule.runOnIdle { viewModels.clear() }
        database.close()
        context.deleteDatabase(name)
    }

    @Test
    fun longScrollDropsAndReloadsPagesAndWritesRefreshVisibleData() {
        seed(1_000)
        lateinit var items: LazyPagingItems<HistoryListItem>
        composeRule.setContent {
            val flow = remember { repository.observeHistory().map { it.withDateHeaders() } }
            items = flow.collectAsLazyPagingItems()
            MaterialTheme { HistoryScreen(items, {}, {}, {}, {}) }
        }
        fun visits() = items.itemSnapshotList.items.filterIsInstance<HistoryListItem.Visit>().map { it.entry }
        composeRule.waitUntil(10_000) { visits().size >= 100 }
        assertTrue(visits().size < 1_000)
        assertEquals(2, items.itemSnapshotList.items.filterIsInstance<HistoryListItem.Day>().size)
        repeat(12) {
            val oldest = visits().last().id
            composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(items.itemCount - 1)
            composeRule.waitUntil(10_000) { visits().last().id < oldest }
            val keys = items.itemSnapshotList.items.map { it.key }
            assertEquals("Page boundaries must not duplicate date headers or visits", keys.distinct().size, keys.size)
        }
        assertTrue("Distant pages should be dropped", visits().first().id < 1_000)
        assertTrue("Metadata should remain bounded", visits().size <= 300)
        val first = visits().first().id
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        composeRule.waitUntil(10_000) { visits().first().id > first }
        assertTrue(visits().size <= 300)
        val selected = visits()[20]
        runBlocking { repository.remove(selected.id) }
        composeRule.waitUntil(10_000) { visits().none { it.id == selected.id } }
        runBlocking { repository.clear() }
        composeRule.waitUntil(10_000) { items.itemCount == 0 }
        composeRule.onNodeWithText("A little rewind").assertIsDisplayed()
        runBlocking {
            repository.recordView(ExtractionResult("https://example.com/new", null, "New visit", null, null, null, emptyList()))
        }
        composeRule.waitUntil(10_000) { visits().any { it.title == "New visit" } }
        composeRule.onNodeWithText("New visit").assertIsDisplayed()
        composeRule.onAllNodesWithText("Today").assertCountEquals(1)
    }

    @Test
    fun reopeningHistoryStartsAtNewestAfterScrollingAcrossPages() {
        seed(500)
        val visible = mutableStateOf(true)
        val fullyHidden = mutableStateOf(false)
        val pagerCount = AtomicInteger()
        val countedRepository = object : HistoryRepository by repository {
            override fun observeHistory() = repository.observeHistory().also { pagerCount.incrementAndGet() }
        }
        val viewModel = HistoryViewModel(context.applicationContext as Application, countedRepository)
        viewModels.put("history", viewModel)
        composeRule.setContent {
            MaterialTheme { HistoryRoute(viewModel, {}, {}, visible.value, fullyHidden.value) }
        }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("Visit 500").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(95)
        // Cross the initial page boundary once the newly prefetched rows reach the UI.
        composeRule.waitUntil(10_000) {
            runCatching {
                composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(140)
            }.isSuccess
        }
        composeRule.onNodeWithText("Visit 500").assertDoesNotExist()
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        // Keep the outgoing list unchanged until the return transition completes.
        composeRule.onNodeWithText("Visit 500").assertDoesNotExist()
        composeRule.runOnIdle { fullyHidden.value = true }
        // Already at the top before the next opening transition starts.
        composeRule.onNodeWithText("Visit 500").assertIsDisplayed()
        composeRule.runOnIdle { visible.value = true; fullyHidden.value = false }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("Visit 500").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Visit 500").assertIsDisplayed()
        composeRule.onNodeWithText("Loading history…").assertDoesNotExist()
        assertEquals("Reopening must reuse the cached newest pages", 1, pagerCount.get())
    }

    @Test
    fun reopeningAfterPageEvictionKeepsRowsVisibleWhileNewestBatchLoads() {
        seed(1_000)
        val visible = mutableStateOf(true)
        val fullyHidden = mutableStateOf(false)
        val pagerCount = AtomicInteger()
        val reloadGate = CompletableDeferred<Unit>()
        val delayedRepository = object : HistoryRepository by repository {
            override fun observeHistory() = repository.observeHistory().let { flow ->
                val generation = pagerCount.incrementAndGet()
                flow.onEach { if (generation > 1) reloadGate.await() }
            }
        }
        val viewModel = HistoryViewModel(context.applicationContext as Application, delayedRepository)
        viewModels.put("history", viewModel)
        lateinit var snapshot: LazyPagingItems<HistoryListItem>
        composeRule.setContent {
            // Observe the shared bounded cache without issuing load hints from this collector.
            snapshot = viewModel.history.collectAsLazyPagingItems()
            MaterialTheme { HistoryRoute(viewModel, {}, {}, visible.value, fullyHidden.value) }
        }
        fun visits() = snapshot.itemSnapshotList.items.filterIsInstance<HistoryListItem.Visit>()
        composeRule.waitUntil(10_000) { visits().size >= 100 }
        repeat(6) {
            val oldest = visits().last().entry.id
            composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(snapshot.itemCount - 1)
            composeRule.waitUntil(10_000) { visits().last().entry.id < oldest }
        }
        assertTrue(visits().first().entry.id < 1_000)
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { fullyHidden.value = true }
        // Reload evicted pages while History is still offscreen.
        composeRule.waitUntil(10_000) { pagerCount.get() == 2 }
        composeRule.runOnIdle { visible.value = true; fullyHidden.value = false }
        composeRule.onNodeWithText("Loading history…").assertDoesNotExist()
        composeRule.onNode(hasScrollToIndexAction()).assertExists()
        composeRule.onNodeWithText("Clear all").assertIsDisplayed()
        reloadGate.complete(Unit)
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("Visit 1000").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Visit 1000").assertIsDisplayed()
        assertEquals(2, pagerCount.get())
    }

    @Test
    fun failedInitialAndAppendLoadsCanBeRetriedWithoutLosingRows() {
        var failRefresh = true
        var failAppend = true
        lateinit var items: LazyPagingItems<HistoryListItem>
        val history = Pager(PagingConfig(pageSize = 20, initialLoadSize = 20, prefetchDistance = 2, enablePlaceholders = false)) {
            object : PagingSource<Int, HistoryEntry>() {
                override fun getRefreshKey(state: PagingState<Int, HistoryEntry>): Int? = null
                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, HistoryEntry> {
                    if (params is LoadParams.Refresh && failRefresh) {
                        failRefresh = false
                        return LoadResult.Error(IllegalStateException("Test refresh failure"))
                    }
                    if (params is LoadParams.Append && failAppend) {
                        failAppend = false
                        return LoadResult.Error(IllegalStateException("Test append failure"))
                    }
                    val start = params.key ?: 0
                    return LoadResult.Page(
                        (start until start + 20).map { index ->
                            HistoryEntry(index.toLong(), "https://example.com/$index", null, "Retry visit $index", null,
                                HistoryMediaKind.Video, 1, null, now)
                        }, null, if (start == 0) 20 else null,
                    )
                }
            }
        }.flow.map { it.withDateHeaders() }
        composeRule.setContent {
            items = history.collectAsLazyPagingItems()
            MaterialTheme { HistoryScreen(items, {}, {}, {}, {}) }
        }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.waitUntil(10_000) { items.itemCount == 21 }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(20)
        composeRule.waitUntil(10_000) { items.loadState.append is androidx.paging.LoadState.Error }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(21)
        composeRule.onNodeWithText("Couldn’t load history. Retry").performClick()
        composeRule.waitUntil(10_000) { items.itemCount == 41 }
        assertEquals((0L until 40L).toList(), items.itemSnapshotList.items.filterIsInstance<HistoryListItem.Visit>().map { it.entry.id })
    }

    private fun seed(count: Int) {
        database.writableDatabase.beginTransaction()
        try {
            repeat(count) { index ->
                database.insert(HistoryEntry(0, "https://example.com/$index", null, "Visit ${index + 1}", null,
                    HistoryMediaKind.Video, 1, null, now - ((count - index - 1) / 75) * 86_400_000L))
            }
            database.writableDatabase.setTransactionSuccessful()
        } finally { database.writableDatabase.endTransaction() }
    }
}
