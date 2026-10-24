package io.github.originalrecipe1.unfurlit.intents

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenLinkTargetsTest {
    private val unfurlit = LinkHandler(OWN_PACKAGE, "$OWN_PACKAGE.MainActivity")
    private val browser = LinkHandler("org.mozilla.fennec_fdroid", "org.mozilla.fenix.IntentReceiverActivity")
    private val instagram = LinkHandler("com.instagram.android", "com.instagram.url.UrlHandlerActivity")
    private val chrome = LinkHandler("com.android.chrome", "com.google.android.apps.chrome.IntentDispatcher")
    private val systemPicker = LinkHandler("android", "com.android.internal.app.ResolverActivity")

    @Test
    fun `launches the default handler when it is another app`() {
        assertEquals(
            OpenLinkTarget.Handler(instagram),
            OpenLinkTargets.select(OWN_PACKAGE, instagram, listOf(unfurlit, browser, instagram)),
        )
    }

    @Test
    fun `skips Unfurlit when it is the default`() {
        assertEquals(
            OpenLinkTarget.Handler(browser),
            OpenLinkTargets.select(OWN_PACKAGE, unfurlit, listOf(unfurlit, browser)),
        )
        assertEquals(
            OpenLinkTarget.Chooser(listOf(unfurlit)),
            OpenLinkTargets.select(OWN_PACKAGE, unfurlit, listOf(unfurlit, browser, instagram)),
        )
    }

    @Test
    fun `launches the only other handler`() {
        assertEquals(
            OpenLinkTarget.Handler(browser),
            OpenLinkTargets.select(OWN_PACKAGE, null, listOf(unfurlit, browser)),
        )
    }

    @Test
    fun `offers a chooser without Unfurlit when several apps can open the link`() {
        assertEquals(
            OpenLinkTarget.Chooser(listOf(unfurlit)),
            OpenLinkTargets.select(OWN_PACKAGE, systemPicker, listOf(unfurlit, browser, instagram)),
        )
        assertEquals(
            OpenLinkTarget.Chooser(emptyList()),
            OpenLinkTargets.select(OWN_PACKAGE, null, listOf(browser, instagram)),
        )
    }

    @Test
    fun `reports none when only Unfurlit can open the link`() {
        assertEquals(OpenLinkTarget.None, OpenLinkTargets.select(OWN_PACKAGE, unfurlit, listOf(unfurlit)))
        assertEquals(OpenLinkTarget.None, OpenLinkTargets.select(OWN_PACKAGE, null, emptyList()))
    }

    @Test
    fun `falls back to the default browser when only Unfurlit is listed for the site`() {
        // A site turned on for Unfurlit: the link's own query lists nothing else.
        assertEquals(
            OpenLinkTarget.Handler(chrome),
            OpenLinkTargets.select(OWN_PACKAGE, unfurlit, listOf(unfurlit), chrome, listOf(browser, chrome)),
        )
        assertEquals(
            OpenLinkTarget.Handler(browser),
            OpenLinkTargets.select(OWN_PACKAGE, unfurlit, listOf(unfurlit), systemPicker, listOf(browser)),
        )
    }

    @Test
    fun `offers the browsers when none is the default`() {
        assertEquals(
            OpenLinkTarget.Browsers(listOf(browser, chrome)),
            OpenLinkTargets.select(OWN_PACKAGE, unfurlit, listOf(unfurlit), systemPicker, listOf(browser, chrome)),
        )
    }

    @Test
    fun `ignores the browsers while another app is listed for the link`() {
        assertEquals(
            OpenLinkTarget.Handler(instagram),
            OpenLinkTargets.select(OWN_PACKAGE, unfurlit, listOf(unfurlit, instagram), chrome, listOf(chrome)),
        )
    }

    @Test
    fun `never falls back to Unfurlit as a browser`() {
        assertEquals(
            OpenLinkTarget.None,
            OpenLinkTargets.select(OWN_PACKAGE, unfurlit, listOf(unfurlit), unfurlit, listOf(unfurlit)),
        )
    }

    private companion object {
        const val OWN_PACKAGE = "io.github.originalrecipe1.unfurlit"
    }
}
