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
