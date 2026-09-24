package io.github.originalrecipe1.unfurlit.data.extractor.reddit

import org.junit.Assert.assertEquals
import org.junit.Test

class RedditLinksTest {
    @Test
    fun alternativeFrontEndPostsUseReddit() {
        assertEquals(
            "https://www.reddit.com/r/codex/comments/1woxxj2/this_needs_more_attention/",
            RedditLinks.normalize("https://eddrit.com/r/codex/comments/1woxxj2/this_needs_more_attention/"),
        )
        assertEquals(
            "https://www.reddit.com/r/pics/comments/abc123",
            RedditLinks.normalize("https://redlib.example.org/r/pics/comments/abc123?context=3"),
        )
    }

    @Test
    fun previewImagesMapToTheOriginal() {
        assertEquals(
            "https://i.redd.it/4pwb0mds2grh1.png",
            RedditLinks.normalize(
                "https://preview.redd.it/this-needs-more-attention-v0-4pwb0mds2grh1.png" +
                    "?width=1080&crop=smart&auto=webp&s=0123abcd",
            ),
        )
        assertEquals(
            "https://i.redd.it/4pwb0mds2grh1.jpeg",
            RedditLinks.normalize("https://preview.redd.it/4pwb0mds2grh1.jpeg?width=640&format=pjpg"),
        )
    }

    @Test
    fun mediaWrapperUnwrapsRedditImages() {
        assertEquals(
            "https://i.redd.it/4pwb0mds2grh1.png",
            RedditLinks.normalize("https://www.reddit.com/media?url=https%3A%2F%2Fi.redd.it%2F4pwb0mds2grh1.png"),
        )
        assertEquals(
            "https://i.redd.it/4pwb0mds2grh1.png",
            RedditLinks.normalize(
                "https://www.reddit.com/media?url=https%3A%2F%2Fpreview.redd.it%2Ftitle-v0-4pwb0mds2grh1.png%3Fwidth%3D640",
            ),
        )
    }

    @Test
    fun postsAreKeptWhenRedditRedirectsToAGate() {
        val post = "https://www.reddit.com/r/okbuddycinephile/comments/1wop7o6/what_happened_to_orlando_bloom/"
        assertEquals(post, RedditLinks.keepPostOverGate(post, "https://www.reddit.com/over18?dest=https%3A%2F%2Fwww.reddit.com%2Fr%2Fx"))
        assertEquals(post, RedditLinks.keepPostOverGate(post, "https://www.reddit.com/login/?dest=x"))
        // Redirects to another post URL, off Reddit, or from non-post links are followed.
        val canonical = "https://www.reddit.com/r/okbuddycinephile/comments/1wop7o6/what_happened_to_orlando_bloom/?rdt=1"
        assertEquals(canonical, RedditLinks.keepPostOverGate(post, canonical))
        assertEquals("https://example.com/x", RedditLinks.keepPostOverGate(post, "https://example.com/x"))
        val share = "https://www.reddit.com/r/okbuddycinephile/s/AbCdEf1234"
        assertEquals(post, RedditLinks.keepPostOverGate(share, post))
        assertEquals("https://www.reddit.com/login/", RedditLinks.keepPostOverGate(share, "https://www.reddit.com/login/"))
    }

    @Test
    fun postUrlSlugGivesAFallbackTitle() {
        assertEquals(
            "What happened to orlando bloom",
            RedditLinks.titleFromPostUrl(
                "https://www.reddit.com/r/okbuddycinephile/comments/1wop7o6/what_happened_to_orlando_bloom/",
            ),
        )
        assertEquals(
            "This needs more attention",
            RedditLinks.titleFromPostUrl("https://eddrit.com/r/codex/comments/1woxxj2/this_needs_more_attention"),
        )
        assertEquals(null, RedditLinks.titleFromPostUrl("https://www.reddit.com/r/pics/comments/abc123/"))
        assertEquals(null, RedditLinks.titleFromPostUrl("https://i.redd.it/4pwb0mds2grh1.png"))
        assertEquals(null, RedditLinks.titleFromPostUrl("https://x.com/someone/status/123"))
    }

    @Test
    fun leavesEverythingElseUnchanged() {
        listOf(
            "https://www.reddit.com/r/codex/comments/1woxxj2/title/",
            "https://old.reddit.com/r/pics/comments/abc123/",
            "https://i.redd.it/4pwb0mds2grh1.png",
            "https://redd.it/abc123",
            "https://www.reddit.com/media?url=https%3A%2F%2Fevil.example%2Fx.png",
            "https://preview.redd.it/not-an-image-v0-abc.mp4",
            "https://example.com/r/pics/comments/",
            "https://x.com/someone/status/123",
            "not a url",
        ).forEach { url -> assertEquals(url, RedditLinks.normalize(url)) }
    }
}
