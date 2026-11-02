package io.github.originalrecipe1.unfurlit.data.extractor.ytdlp

import io.github.originalrecipe1.unfurlit.data.extractor.reddit.RedditLinks

/** Apply after preflight resolves share/short links and Reddit gate handling keeps the post. */
internal fun redditExtractorOptions(extractionUrl: String): List<String> =
    if (RedditLinks.isPostUrl(extractionUrl)) {
        // RedditIE can return a gallery URL that GenericIE redirects back to the same post.
        // Reject that handoff promptly so gallery-dl can run; named video extractors still work.
        listOf("--use-extractors", "default,-generic")
    } else {
        emptyList()
    }
