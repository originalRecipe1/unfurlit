package io.github.originalrecipe1.unfurlit.ui.components

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.originalrecipe1.unfurlit.ui.theme.UnfurlitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PredictiveBackSurfaceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun previewFromEitherEdgeCancelsWithoutNavigatingAndCommitsOnce() {
        var backs = 0
        composeRule.setContent {
            UnfurlitTheme {
                PredictiveBackSurface(
                    onBack = { backs++ },
                    preview = { Text("Home preview") },
                ) {
                    Box(Modifier.fillMaxSize().testTag("viewer"))
                }
            }
        }
        val dispatcher = composeRule.activity.onBackPressedDispatcher
        val initialBounds = composeRule.onNodeWithTag("viewer").fetchSemanticsNode().boundsInRoot
        for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
            composeRule.runOnUiThread {
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 500f, 0f, edge))
            }
            composeRule.waitForIdle()
            composeRule.runOnUiThread {
                dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 500f, 0.25f, edge))
            }
            composeRule.waitForIdle()
            composeRule.runOnUiThread {
                dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 650f, 0.5f, edge))
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Home preview").assertIsDisplayed()
            val bounds = composeRule.onNodeWithTag("viewer").fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width < initialBounds.width)
            assertTrue("The card follows vertical finger movement", bounds.center.y > initialBounds.center.y)
            assertEquals(0, backs)
            composeRule.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
            composeRule.waitForIdle()
            assertEquals(initialBounds, composeRule.onNodeWithTag("viewer").fetchSemanticsNode().boundsInRoot)
            composeRule.onNodeWithText("Home preview").assertDoesNotExist()
            assertEquals(0, backs)
        }
        composeRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 500f, 0f, BackEventCompat.EDGE_LEFT))
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 500f, 0.7f, BackEventCompat.EDGE_LEFT))
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, backs)
        // Deliberately leave navigation unapplied: the dismissed surface must never flash back.
        composeRule.onNodeWithTag("viewer").assertDoesNotExist()
        composeRule.onNodeWithText("Home preview").assertIsDisplayed()
    }

    @Test
    fun regularBackCommitsWithoutAPreview() {
        var backs = 0
        composeRule.setContent {
            PredictiveBackSurface(onBack = { backs++ }, preview = { Text("Home preview") }) {
                Text("Viewer")
            }
        }
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, backs)
        composeRule.onNodeWithText("Home preview").assertDoesNotExist()
    }
}
