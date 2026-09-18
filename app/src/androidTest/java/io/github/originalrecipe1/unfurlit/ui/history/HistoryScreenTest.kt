package io.github.originalrecipe1.unfurlit.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import io.github.originalrecipe1.unfurlit.domain.model.HistoryEntry
import io.github.originalrecipe1.unfurlit.domain.model.HistoryMediaKind

class HistoryScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private val entry = HistoryEntry(7, "https://example.com/watch", "Example", "An afternoon outside", "A creator", HistoryMediaKind.Video, 1, 62, System.currentTimeMillis())

    @Test
    fun newVisitStartsAtTopButDataRefreshDoesNotResetCurrentScroll() {
        val visible = mutableStateOf(true)
        val entries = mutableStateOf((0L until 100L).map {
            entry.copy(id = it, title = "Visit $it")
        })
        composeRule.setContent {
            MaterialTheme {
                HistoryScreen(paged(entries.value), {}, {}, {}, {}, visible.value)
            }
        }
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Visit 70"))
        composeRule.onNodeWithText("Visit 70").assertIsDisplayed()
        composeRule.runOnIdle { entries.value = entries.value.map { it.copy(author = "Updated") } }
        composeRule.onNodeWithText("Visit 70").assertIsDisplayed()
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { visible.value = true }
        composeRule.onNodeWithText("Visit 0").assertIsDisplayed()
        composeRule.onNodeWithText("Visit 70").assertDoesNotExist()
    }

    @Test
    fun rowOpensMediaAndRemoveIconDeletesOnlyTheSelectedVisit() {
        var opened: Long? = null
        var removed: Long? = null
        composeRule.setContent {
            MaterialTheme { HistoryScreen(paged(listOf(entry)), {}, { opened = it.id }, { removed = it }, {}) }
        }
        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText(entry.title!!).performClick()
        composeRule.runOnIdle { assertEquals(7L, opened) }
        composeRule.runOnIdle { opened = null }
        composeRule.onNodeWithContentDescription("Remove ${entry.title} from history").performClick()
        composeRule.runOnIdle { assertEquals(null, opened) }
        composeRule.runOnIdle { assertEquals(7L, removed) }
    }

    @Test
    fun clearRequiresConfirmationAndCancelPreservesHistory() {
        var cleared = 0
        composeRule.setContent {
            MaterialTheme { HistoryScreen(paged(listOf(entry)), {}, {}, {}, { cleared++ }) }
        }
        composeRule.onNodeWithText("Clear all").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertEquals(0, cleared) }
        composeRule.onNodeWithText("Clear all").performClick()
        composeRule.onNodeWithText("Clear history").performClick()
        composeRule.runOnIdle { assertEquals(1, cleared) }
    }

    @Test
    fun emptyHistoryOffersBackWithoutDestructiveActions() {
        var wentBack = false
        composeRule.setContent {
            MaterialTheme { HistoryScreen(paged(emptyList()), { wentBack = true }, {}, {}, {}) }
        }
        composeRule.onNodeWithText("A little rewind").assertIsDisplayed()
        composeRule.onNodeWithText("Clear all").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.runOnIdle { assertEquals(true, wentBack) }
    }
}

@Composable
private fun paged(entries: List<HistoryEntry>) = remember(entries) {
    flowOf(PagingData.from(
        entries,
        sourceLoadStates = LoadStates(
            LoadState.NotLoading(true), LoadState.NotLoading(true), LoadState.NotLoading(true),
        ),
    ).withDateHeaders())
}.collectAsLazyPagingItems()
