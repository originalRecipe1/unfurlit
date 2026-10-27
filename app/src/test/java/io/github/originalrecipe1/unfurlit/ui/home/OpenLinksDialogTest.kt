package io.github.originalrecipe1.unfurlit.ui.home

import io.github.originalrecipe1.unfurlit.intents.SelectedDomains
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenLinksDialogTest {
    @Test
    fun `closes after Settings once a site is on`() {
        assertTrue(closesAfterSettings(openedSettings = true, SelectedDomains(enabled = 1, total = 40)))
        assertTrue(closesAfterSettings(openedSettings = true, SelectedDomains(enabled = 40, total = 40)))
    }

    @Test
    fun `stays open after Settings while no site is on`() {
        assertFalse(closesAfterSettings(openedSettings = true, SelectedDomains(enabled = 0, total = 40)))
    }

    @Test
    fun `stays open when the selection can't be read`() {
        assertFalse(closesAfterSettings(openedSettings = true, domains = null))
    }

    @Test
    fun `stays open before Settings was opened`() {
        assertFalse(closesAfterSettings(openedSettings = false, SelectedDomains(enabled = 3, total = 40)))
    }
}
