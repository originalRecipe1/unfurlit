package io.github.originalrecipe1.unfurlit.intents

/** An activity that can handle a web link, named without Android types so selection stays testable. */
data class LinkHandler(val packageName: String, val className: String)

sealed interface OpenLinkTarget {
    /** Launch this handler directly. */
    data class Handler(val handler: LinkHandler) : OpenLinkTarget

    /** Let the user pick; [excluded] are Unfurlit's own handlers. */
    data class Chooser(val excluded: List<LinkHandler>) : OpenLinkTarget

    /** Only Unfurlit can open the link. */
    data object None : OpenLinkTarget
}

object OpenLinkTargets {
    /**
     * Picks where Open link goes without routing the link back into Unfurlit.
     *
     * [defaultHandler] is what the system resolves the link to. Without a set default that is
     * the system's own picker, which isn't in [handlers] and so is ignored like Unfurlit is.
     */
    fun select(
        ownPackage: String,
        defaultHandler: LinkHandler?,
        handlers: List<LinkHandler>,
    ): OpenLinkTarget {
        val (own, others) = handlers.distinct().partition { it.packageName == ownPackage }
        return when {
            defaultHandler != null && defaultHandler in others -> OpenLinkTarget.Handler(defaultHandler)
            others.isEmpty() -> OpenLinkTarget.None
            others.size == 1 -> OpenLinkTarget.Handler(others.single())
            else -> OpenLinkTarget.Chooser(own)
        }
    }
}
