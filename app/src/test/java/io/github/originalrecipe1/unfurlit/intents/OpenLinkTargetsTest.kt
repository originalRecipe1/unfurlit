package io.github.originalrecipe1.unfurlit.intents

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenLinkTargetsTest {
    private val unfurlit = LinkHandler(OWN_PACKAGE, "$OWN_PACKAGE.MainActivity")
    private val browser = LinkHandler("org.mozilla.fennec_fdroid", "org.mozilla.fenix.IntentReceiverActivity")
    private val instagram = LinkHandler("com.instagram.android", "com.instagram.url.UrlHandlerActivity")
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

    private companion object {
        const val OWN_PACKAGE = "io.github.originalrecipe1.unfurlit"
    }
}
