package io.github.originalrecipe1.unfurlit.data.extractor.ytdlp

import io.github.originalrecipe1.unfurlit.data.extractor.reddit.RedditLinks
import org.junit.Assert.assertEquals
import org.junit.Test

class RedditExtractorOptionsTest {
    private val withoutGeneric = listOf("--use-extractors", "default,-generic")

    @Test
    fun redditPostsDisableOnlyTheGenericExtractor() {
        listOf(
            "https://www.reddit.com/r/announcements/comments/hrrh23/now_you_can_make_posts_with_multiple_images/",
            "https://www.reddit.com/comments/hrrh23",
            "https://old.reddit.com/comments/hrrh23/?context=3",
            "https://www.reddit.com/gallery/hrrh23",
            "https://www.reddit.com/gallery/hrrh23/",
            "https://www.reddit.com/user/example-user/comments/hrrh23/title/",
            "https://www.reddit.com/u/example-user/comments/hrrh23/",
        ).forEach { url -> assertEquals(url, withoutGeneric, redditExtractorOptions(url)) }
    }

    @Test
    fun resolvedShareLinksAndPostsRetainedOverGatesUseTheOption() {
        val post = "https://www.reddit.com/comments/hrrh23"
        for (original in listOf("https://redd.it/hrrh23", "https://www.reddit.com/r/pics/s/AbCdEf1234")) {
            assertEquals(withoutGeneric, redditExtractorOptions(RedditLinks.keepPostOverGate(original, post)))
        }
        assertEquals(withoutGeneric,
            redditExtractorOptions(RedditLinks.keepPostOverGate(post, "https://www.reddit.com/login/")))
    }

    @Test
    fun otherSitesImagesAndUnresolvedShareLinksKeepDefaultExtractors() {
        listOf(
            "https://example.com/r/pics/comments/hrrh23/title/",
            "https://www.reddit.com.example.com/comments/hrrh23",
            "https://www.youtube.com/watch?v=jNQXAC9IVRw",
            "https://i.redd.it/fs12v3j0z1b51.png",
            "https://preview.redd.it/00af44lpn0u51.jpg?width=960",
            "https://redd.it/hrrh23",
            "https://www.reddit.com/r/pics/s/AbCdEf1234",
            "https://www.reddit.com/r/pics/",
            "not a url",
        ).forEach { url -> assertEquals(url, emptyList<String>(), redditExtractorOptions(url)) }
    }
}
