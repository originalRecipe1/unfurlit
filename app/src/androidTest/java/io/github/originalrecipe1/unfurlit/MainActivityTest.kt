package io.github.originalrecipe1.unfurlit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.activity.BackEventCompat
import androidx.compose.ui.geometry.Offset
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue

class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun coldLaunchStaysOnTheIdleHomeScreen() {
        composeRule.onNodeWithText("Ready when\nyou are.").assertIsDisplayed()
        composeRule.onNodeWithText("Extracting stream information…").assertDoesNotExist()
        composeRule.onNodeWithText("Open link").assertDoesNotExist()
    }

    @Test
    fun leftSwipeNavigatesFromHomeToHistory() {
        composeRule.onNodeWithText("Ready when\nyou are.").assertIsDisplayed()
        composeRule.onRoot().performTouchInput {
            swipe(Offset(width * 0.8f, height * 0.8f), Offset(width * 0.2f, height * 0.8f))
        }
        composeRule.onNodeWithText("History").assertIsDisplayed()
    }

    @Test
    fun shortLeftSwipeAcrossTheTopBarOpensHistory() {
        val title = composeRule.onNodeWithText("Unfurlit").fetchSemanticsNode().boundsInRoot
        val start = Offset(composeRule.onRoot().fetchSemanticsNode().boundsInRoot.center.x, title.center.y)
        val distance = 64f * composeRule.activity.resources.displayMetrics.density
        composeRule.onRoot().performTouchInput {
            swipe(start, start + Offset(-distance, 0f))
        }
        composeRule.onNodeWithText("History").assertIsDisplayed()
    }

    @Test
    fun shortDiagonalLeftSwipeOpensHistoryWhenHomeFitsOnScreen() {
        val density = composeRule.activity.resources.displayMetrics.density
        composeRule.onRoot().performTouchInput {
            val start = Offset(width * 0.7f, height * 0.8f)
            down(start)
            // A thumb can initially drift vertically before moving left.
            moveTo(start + Offset(-3f, 24f) * density, delayMillis = 80)
            moveTo(start + Offset(-68f, 30f) * density, delayMillis = 160)
            up()
        }
        composeRule.onNodeWithText("History").assertIsDisplayed()
    }

    @Test
    fun swipingBackReturnsHomeAndPreservesTheTypedLink() {
        composeRule.onNode(hasSetTextAction()).performTextInput("https://example.com/video")
        composeRule.runOnUiThread {
            composeRule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(composeRule.activity.window.decorView.windowToken, 0)
        }
        composeRule.onNodeWithContentDescription("Open history").performClick()
        composeRule.onNodeWithText("History").assertIsDisplayed()
        composeRule.onRoot().performTouchInput {
            swipe(Offset(width * 0.2f, height * 0.8f), Offset(width * 0.8f, height * 0.8f))
        }
        composeRule.onNodeWithText("Ready when\nyou are.").assertIsDisplayed()
        composeRule.onNodeWithText("https://example.com/video").assertIsDisplayed()
    }

    @Test
    fun backButtonAnimatesHomeAndHistoryCanBeReopened() {
        composeRule.onNodeWithContentDescription("Open history").performClick()
        composeRule.onNodeWithText("History").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Ready when\nyou are.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open history").performClick()
        composeRule.onNodeWithText("History").assertIsDisplayed()
    }

    @Test
    fun pagesFollowTheFingerBeforeTheSwipeIsReleased() {
        composeRule.onRoot().performTouchInput {
            down(Offset(width * 0.9f, height * 0.8f))
            moveTo(Offset(width * 0.5f, height * 0.8f), delayMillis = 400)
        }
        composeRule.onNodeWithText("History").assertIsDisplayed()
        composeRule.onNodeWithText("Ready when\nyou are.").assertIsDisplayed()
        composeRule.onRoot().performTouchInput { up() }
        composeRule.onNodeWithText("History").assertIsDisplayed()
    }

    @Test
    fun cancelledPredictiveBackStaysInHistoryAndCompletedBackReturnsHome() {
        composeRule.onNodeWithContentDescription("Open history").performClick()
        composeRule.onNodeWithText("History").assertIsDisplayed()
        val dispatcher = composeRule.activity.onBackPressedDispatcher
        fun event(progress: Float) = BackEventCompat(0f, 500f, progress, BackEventCompat.EDGE_LEFT)
        composeRule.runOnUiThread { dispatcher.dispatchOnBackStarted(event(0f)) }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { dispatcher.dispatchOnBackProgressed(event(0.4f)) }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        composeRule.onNodeWithText("History").assertIsDisplayed()
        composeRule.runOnUiThread { dispatcher.dispatchOnBackStarted(event(0f)) }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { dispatcher.dispatchOnBackProgressed(event(0.7f)) }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { dispatcher.onBackPressed() }
        composeRule.onNodeWithText("Ready when\nyou are.").assertIsDisplayed()
    }

    @Test
    fun predictiveBackScalesHistoryAndOffsetsHomeFromEitherSystemEdge() {
        val homeLeft = composeRule.onNodeWithText("Ready when\nyou are.")
            .fetchSemanticsNode().boundsInRoot.left
        composeRule.onNodeWithContentDescription("Open history").performClick()
        val dispatcher = composeRule.activity.onBackPressedDispatcher
        for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
            val originalLeft = composeRule.onNodeWithText("History").fetchSemanticsNode().boundsInRoot.left
            composeRule.runOnUiThread {
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 500f, 0f, edge))
            }
            composeRule.waitForIdle()
            composeRule.runOnUiThread {
                dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 500f, 0.25f, edge))
            }
            composeRule.waitForIdle()
            val previewLeft = composeRule.onNodeWithText("History").fetchSemanticsNode().boundsInRoot.left
            assertTrue("History should move right for Back from edge $edge", previewLeft > originalLeft)
            val previewHomeLeft = composeRule.onNodeWithText("Ready when\nyou are.")
                .fetchSemanticsNode().boundsInRoot.left
            assertTrue("Home should preview from the left beneath History", previewHomeLeft < homeLeft)
            composeRule.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
            composeRule.onNodeWithText("History").assertIsDisplayed()
        }
    }
}
