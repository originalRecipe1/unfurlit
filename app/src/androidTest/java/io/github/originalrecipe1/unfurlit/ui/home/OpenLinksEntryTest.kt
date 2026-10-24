package io.github.originalrecipe1.unfurlit.ui.home

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import io.github.originalrecipe1.unfurlit.intents.OpenLinkSettings
import io.github.originalrecipe1.unfurlit.ui.theme.UnfurlitTheme
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class OpenLinksEntryTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val packageData get() = "package:${composeRule.activity.packageName}"

    @Before
    fun stubSettings() {
        Intents.init()
        // Record the intent without leaving the test activity for the real Settings app.
        intending(anyIntent()).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    @After
    fun releaseIntents() {
        Intents.release()
    }

    @Test
    fun opensThisAppsOpenByDefaultSettings() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        showHome()

        composeRule.onNodeWithText("Open links in Unfurlit").performClick()
        composeRule.onNodeWithText("Open settings").performClick()

        intended(
            allOf(
                hasAction(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS),
                hasData(packageData),
            ),
        )
    }

    @Test
    fun fallsBackToAppInfoWithoutAnOpenByDefaultPage() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val activity = composeRule.activity
        val attempted = mutableListOf<String?>()

        val opened = OpenLinkSettings.open(activity) { intent ->
            attempted += intent.action
            if (intent.action == Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS) {
                throw ActivityNotFoundException()
            }
            activity.startActivity(intent)
        }

        assertTrue(opened)
        assertEquals(
            listOf(
                Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ),
            attempted,
        )
        intended(
            allOf(
                hasAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
                hasData(packageData),
            ),
        )
    }

    @Test
    fun reportsFailureWhenNeitherSettingsPageExists() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

        val opened = OpenLinkSettings.open(composeRule.activity) { throw ActivityNotFoundException() }

        assertTrue(!opened)
    }

    @Test
    fun hidesTheEntryWhereAndroidAlreadyOffersAChooser() {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
        showHome()

        composeRule.onNodeWithText("Supported links and media").assertExists()
        composeRule.onNodeWithText("Open links in Unfurlit").assertDoesNotExist()
    }

    private fun showHome() {
        composeRule.setContent {
            UnfurlitTheme {
                HomeScreen(onOpen = {}, onShowHistory = {})
            }
        }
    }
}
