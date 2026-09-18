package io.github.originalrecipe1.unfurlit.ui.viewer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.ui.theme.UnfurlitTheme
import org.junit.Assert.assertEquals
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
}
