package io.github.originalrecipe1.unfurlit.intents

/** An activity that can handle a web link, named without Android types so selection stays testable. */
data class LinkHandler(val packageName: String, val className: String)

sealed interface OpenLinkTarget {
    /** Launch this handler directly. */
    data class Handler(val handler: LinkHandler) : OpenLinkTarget

    /** Let the user pick; [excluded] are Unfurlit's own handlers. */
    data class Chooser(val excluded: List<LinkHandler>) : OpenLinkTarget

    /** Let the user pick one of these browsers, which the link's own query left out. */
    data class Browsers(val browsers: List<LinkHandler>) : OpenLinkTarget

    /** Only Unfurlit can open the link. */
    data object None : OpenLinkTarget
}

object OpenLinkTargets {
    /**
     * Picks where Open link goes without routing the link back into Unfurlit.
     *
     * [defaultHandler] is what the system resolves the link to. Without a set default that is
     * the system's own picker, which isn't in [handlers] and so is ignored like Unfurlit is.
     *
     * Once the user turns a site on for Unfurlit, Android 12+ lists only Unfurlit for its links.
     * [browsers] and [defaultBrowser] then come from a query without a site, so Open link can
     * still reach a browser.
     */
    fun select(
        ownPackage: String,
        defaultHandler: LinkHandler?,
        handlers: List<LinkHandler>,
        defaultBrowser: LinkHandler? = null,
        browsers: List<LinkHandler> = emptyList(),
    ): OpenLinkTarget {
        val (own, others) = handlers.distinct().partition { it.packageName == ownPackage }
        return when {
            defaultHandler != null && defaultHandler in others -> OpenLinkTarget.Handler(defaultHandler)
            others.size == 1 -> OpenLinkTarget.Handler(others.single())
            others.isNotEmpty() -> OpenLinkTarget.Chooser(own)
            else -> selectBrowser(ownPackage, defaultBrowser, browsers)
        }
    }

    private fun selectBrowser(
        ownPackage: String,
        defaultBrowser: LinkHandler?,
        browsers: List<LinkHandler>,
    ): OpenLinkTarget {
        val choices = browsers.distinct().filterNot { it.packageName == ownPackage }
        return when {
            defaultBrowser != null && defaultBrowser in choices -> OpenLinkTarget.Handler(defaultBrowser)
            choices.isEmpty() -> OpenLinkTarget.None
            choices.size == 1 -> OpenLinkTarget.Handler(choices.single())
            else -> OpenLinkTarget.Browsers(choices)
        }
    }
}
