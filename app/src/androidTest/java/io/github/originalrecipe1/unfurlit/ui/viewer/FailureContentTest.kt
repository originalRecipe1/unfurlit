package io.github.originalrecipe1.unfurlit.ui.viewer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.originalrecipe1.unfurlit.domain.model.BlockingPlatform
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.ui.theme.UnfurlitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FailureContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unsupportedLinkOffersOriginalAndAnotherLinkWithoutRetryOrVersion() {
        var action = ""
        compose.setContent {
            UnfurlitTheme {
                FailureContent(ExtractionError.UnsupportedUrl, { action = "retry" },
                    { action = "original" }, { action = "home" })
            }
        }
        compose.onNodeWithText("No viewable media found").assertIsDisplayed()
        compose.onNodeWithText("yt-dlp", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Open link").performClick()
        compose.runOnIdle { assertEquals("original", action) }
        compose.onNodeWithText("Try another link").performClick()
        compose.runOnIdle { assertEquals("home", action) }
    }

    @Test fun networkFailureOffersRetry() {
        var retries = 0
        compose.setContent {
            UnfurlitTheme {
                FailureContent(ExtractionError.NetworkFailure, { retries++ }, {}, {})
            }
        }
        compose.onNodeWithText("Couldn’t reach this site").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun redditBlockLeadsWithOpenLinkWithoutSuggestingAnotherConnection() {
        var action = ""
        compose.setContent {
            UnfurlitTheme {
                FailureContent(ExtractionError.NetworkBlocked(BlockingPlatform.Reddit), { action = "retry" },
                    { action = "original" }, {})
            }
        }
        compose.onNodeWithText("Reddit blocked this request").assertIsDisplayed()
        compose.onNodeWithText(
            "Reddit is currently blocking apps that aren’t signed in. Use Open link to view it on Reddit, " +
                "or try again later.",
        ).assertIsDisplayed()
        compose.onNodeWithText("different connection", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Sign-in may be needed").assertDoesNotExist()
        val openLink = compose.onNodeWithText("Open link").getUnclippedBoundsInRoot()
        val tryAgain = compose.onNodeWithText("Try again").getUnclippedBoundsInRoot()
        assertTrue("Open link should come before Try again", openLink.bottom <= tryAgain.top)
        compose.onNodeWithText("Open link").performClick()
        compose.runOnIdle { assertEquals("original", action) }
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals("retry", action) }
    }

    @Test fun youTubeBlockSuggestsAnotherConnection() {
        var retries = 0
        compose.setContent {
            UnfurlitTheme {
                FailureContent(ExtractionError.NetworkBlocked(BlockingPlatform.YouTube), { retries++ }, {}, {})
            }
        }
        compose.onNodeWithText("YouTube blocked this request").assertIsDisplayed()
        compose.onNodeWithText(
            "YouTube blocked this request from your network. Try again later or on a different connection.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
