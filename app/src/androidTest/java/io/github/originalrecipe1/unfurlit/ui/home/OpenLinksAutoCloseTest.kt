package io.github.originalrecipe1.unfurlit.ui.home

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import io.github.originalrecipe1.unfurlit.intents.SelectedDomains
import io.github.originalrecipe1.unfurlit.ui.theme.UnfurlitTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The Open links dialog closes on return from Settings only once a site is on. */
class OpenLinksAutoCloseTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Volatile
    private var enabled = 0

    @Before
    fun requireOpenByDefault() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    }

    @Test
    fun staysOpenWhenNoSiteIsOnAfterSettings() {
        showDialog()

        composeRule.onNodeWithText("Open settings").performClick()
        returnFromSettings()

        composeRule.onNodeWithText(TITLE).assertExists()
        composeRule.onNodeWithText("Not turned on yet").assertExists()
    }

    @Test
    fun closesOnceASiteIsOnAfterSettings() {
        showDialog()
        composeRule.onNodeWithText("Not turned on yet").assertExists()

        composeRule.onNodeWithText("Open settings").performClick()
        enabled = 1
        returnFromSettings()

        composeRule.onNodeWithText(TITLE).assertDoesNotExist()
    }

    @Test
    fun staysOpenWhenASiteWasAlreadyOn() {
        enabled = 2
        showDialog()

        returnFromSettings()

        composeRule.onNodeWithText(TITLE).assertExists()
        composeRule.onNodeWithText("Turned on").assertExists()
    }

    private fun showDialog() {
        composeRule.setContent {
            UnfurlitTheme {
                var show by remember { mutableStateOf(true) }
                if (show) {
                    OpenLinksDialog(
                        onDismissRequest = { show = false },
                        readSelectedDomains = { SelectedDomains(enabled = enabled, total = 40) },
                        // Stands in for Settings; returnFromSettings() pauses and resumes the activity.
                        openSettings = { true },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun returnFromSettings() {
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
    }

    private companion object {
        const val TITLE = "Open links in Unfurlit"
    }
}
